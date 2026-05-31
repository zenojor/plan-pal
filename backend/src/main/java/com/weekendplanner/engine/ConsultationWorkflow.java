package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.ExperiencePreference;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.PoiPreview;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.provider.PoiProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class ConsultationWorkflow {

    private static final Logger log = LoggerFactory.getLogger(ConsultationWorkflow.class);
    private static final int MIN_CHOICE_OPTIONS = 2;
    private static final int MAX_CHOICE_OPTIONS = 5;

    private final ChatModel chatModel;
    private final IntentExtractor intentExtractor;
    private final PlanExecutionStore executionStore;
    private final SessionStateStore sessionStateStore;
    private final ObjectMapper objectMapper;
    private final ChoiceBarTool choiceBarTool;
    private final PoiProvider poiProvider;
    private final ContextualResearchPlanner contextualResearchPlanner;
    private final PlanningAssumptionService planningAssumptionService;
    private final AgentRuntimeProperties runtime;

    public ConsultationWorkflow(ChatModel chatModel,
                                IntentExtractor intentExtractor,
                                PlanExecutionStore executionStore,
                                SessionStateStore sessionStateStore,
                                ObjectMapper objectMapper,
                                PoiProvider poiProvider,
                                ContextualResearchPlanner contextualResearchPlanner,
                                PlanningAssumptionService planningAssumptionService,
                                AgentRuntimeProperties runtime) {
        this.chatModel = chatModel;
        this.intentExtractor = intentExtractor;
        this.executionStore = executionStore;
        this.sessionStateStore = sessionStateStore;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.choiceBarTool = new ChoiceBarTool();
        this.poiProvider = poiProvider;
        this.contextualResearchPlanner = contextualResearchPlanner == null ? new ContextualResearchPlanner() : contextualResearchPlanner;
        this.planningAssumptionService = planningAssumptionService == null ? new PlanningAssumptionService() : planningAssumptionService;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    public PlanResponse start(PlanRequest request, InitialRouteCommand route, Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent intent = consultingIntent(request);
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), "");
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);

        emitter.accept(new SseEvent("START", 0, "consult.start: understand open-ended request",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("ACTION", 1, "consult.respond: explain options and decide whether to render choice bar",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        ConsultResult result = consult(request.prompt(), planId);
        sessionStateStore.savePending(planId, request.userId(),
                new PendingAction("SELECT_PREFERENCE", null, null, List.of("choose preference", "ask follow-up", "build plan")),
                new RecentEvent(RecentEventType.CHOICE_BAR_RENDERED, "Consultation choice bar rendered", Instant.now()));

        emitter.accept(new SseEvent("THOUGHT", 2, result.message(), List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, result.card()));
        emitter.accept(new SseEvent("FINISH", 3, result.message(), List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, result.card()));
        return response(planId, request.userId(), intent, result.message());
    }

    public void continueAfterPreference(AgentContext context, Consumer<SseEvent> emitter) {
        PlanIntent intent = context.draft().intent();
        String preference = normalizePreference(context.userInput());
        ConstraintSet baseConstraints = context.sessionState() == null
                ? ConstraintSet.fromIntent(intent)
                : context.sessionState().userConstraints();
        ConstraintSet constraints = baseConstraints.withExperiencePreference(ExperiencePreference.fromPreferenceKey(preference));
        sessionStateStore.savePreference(context.draft().planId(), context.draft().userId(), constraints,
                new PendingAction("ASK_CONTEXT", null, null, List.of("time", "location", "headcount", "build plan")),
                new RecentEvent(RecentEventType.PREFERENCE_SELECTED, "Preference selected: " + preference, Instant.now()));

        String message = nextQuestion(preference);
        emitter.accept(new SseEvent("ACTION", 2, "consult.preference: save selected preference",
                context.draft().timeline(), null, null, null, null, context.draft().planId(), intent,
                context.draft().orderIntents(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                null, context.draft().planId(), intent, context.draft().orderIntents(), "PENDING_CONFIRMATION"));
    }

    public void continueAfterContext(AgentContext context, Consumer<SseEvent> emitter) {
        PlanIntent intent = context.draft().intent();
        ConstraintSet baseConstraints = context.sessionState() == null
                ? ConstraintSet.fromIntent(intent)
                : context.sessionState().userConstraints();
        ExperiencePreference preference = baseConstraints.experiencePreference()
                .withContext(extractTimeHint(context.userInput()), extractLocationHint(context.userInput()));
        ConstraintSet constraints = baseConstraints.withExperiencePreference(preference);
        PlanningAssumptionService.AssumptionResult assumptions = planningAssumptionService.apply(intent, constraints);
        if (assumptions.intent() != null && assumptions.executable()) {
            PlanExecutionStore.DraftPlan assumedDraft = new PlanExecutionStore.DraftPlan(
                    context.draft().planId(), context.draft().userId(), assumptions.intent(),
                    context.draft().timeline(), context.draft().orderIntents(), context.draft().notificationText(),
                    context.draft().version(), context.draft().previousVersionId(), context.draft().status(),
                    context.draft().lastConfirmedVersion(), context.draft().idempotencyKey(), java.time.Instant.now());
            executionStore.save(assumedDraft);
            intent = assumptions.intent();
        }
        constraints = assumptions.constraints();
        sessionStateStore.savePreference(context.draft().planId(), context.draft().userId(), constraints,
                new PendingAction("ASK_CONTEXT", null, null, List.of("time", "location", "headcount", "build plan")),
                new RecentEvent(RecentEventType.CONTEXT_UPDATED, "Consultation context updated", Instant.now()));

        emitter.accept(new SseEvent("ACTION", 2, "consult.context: merge time and location",
                context.draft().timeline(), null, null, null, null, context.draft().planId(), intent,
                context.draft().orderIntents(), "PENDING_CONFIRMATION"));

        ContextualResearchPlanner.SearchPlan searchPlan = contextualResearchPlanner.plan(intent.sceneType(), constraints);
        if (searchPlan.needsMoreContext() || poiProvider == null) {
            String message = searchPlan.clarification() == null
                    ? "我还需要一点时间或地点信息，再帮你筛候选。"
                    : searchPlan.clarification();
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, context.draft().planId(), intent, context.draft().orderIntents(), "PENDING_CONFIRMATION"));
            return;
        }

        CandidateCardResult result = buildContextualCard(context, searchPlan);
        if (result.candidateSet().items().isEmpty()) {
            String message = "这组偏好下暂时没找到合适候选。你可以换个区域，或者放宽一点预算/距离。";
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, context.draft().planId(), intent, context.draft().orderIntents(), "PENDING_CONFIRMATION"));
            return;
        }

        sessionStateStore.saveCandidates(context.draft().planId(), context.draft().userId(), result.candidateSet(),
                new PendingAction("SELECT_CANDIDATE", result.candidateSet().candidateSetId(),
                        result.candidateSet().targetSegmentId(), List.of("choose index", "more options", "cancel")),
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Contextual research candidates: " + result.candidateSet().type(), Instant.now()));
        emitter.accept(new SseEvent("ACTION", 3, "contextual.search: preference-aware candidates",
                List.of(), null, null, null, null, context.draft().planId(), intent,
                context.draft().orderIntents(), "PENDING_CONFIRMATION"));
        emitter.accept(new SseEvent("FINISH", 4, searchPlan.description(), List.of(), "SUCCESS", "", "",
                null, context.draft().planId(), intent, context.draft().orderIntents(),
                "PENDING_CONFIRMATION", null, result.card()));
    }

    private ConsultResult consult(String userInput, String planId) {
        if (chatModel != null) {
            try {
                String raw = callModel(consultSystemPrompt(), userInput);
                ConsultResult parsed = parseConsultJson(raw, planId);
                if (parsed != null) return parsed;

                String repaired = callModel(repairSystemPrompt(raw), userInput);
                ConsultResult repairedParsed = parseConsultJson(repaired, planId);
                if (repairedParsed != null) return repairedParsed;
            } catch (Exception e) {
                log.warn("[ConsultationWorkflow] LLM consult failed, using fallback", e);
            }
        }
        return fallback(planId);
    }

    private CandidateCardResult buildContextualCard(AgentContext context, ContextualResearchPlanner.SearchPlan searchPlan) {
        List<ScoredPoi> scored = new ArrayList<>();
        for (ContextualResearchPlanner.SearchQuery query : searchPlan.queries()) {
            List<PoiDto> pois = poiProvider.searchByCategory(query.category(), query.tags(), 5);
            for (PoiDto poi : pois) {
                if (blockedByAvoidTags(poi, searchPlan.avoidTags())) continue;
                scored.add(new ScoredPoi(poi, query.phase(), score(poi, searchPlan)));
            }
        }
        List<ScoredPoi> selected = scored.stream()
                .collect(java.util.stream.Collectors.toMap(
                        value -> value.poi().poiId(),
                        value -> value,
                        (left, right) -> left.score() >= right.score() ? left : right,
                        java.util.LinkedHashMap::new))
                .values()
                .stream()
                .sorted(Comparator.comparingDouble(ScoredPoi::score).reversed())
                .limit(runtime.getCandidateLimit())
                .toList();

        List<ActionCard.ActionOption> options = new ArrayList<>();
        List<CandidateItem> items = new ArrayList<>();
        int index = 1;
        for (ScoredPoi scoredPoi : selected) {
            PoiDto poi = scoredPoi.poi();
            PlanPatch patch = addPatch(scoredPoi.phase(), List.of(runtime.getSelectedPoiPrefix() + poi.poiId(), "CONTEXT_READY"));
            PoiPreview preview = new PoiPreview(poi.poiId(), poi.name(), poi.category(), poi.distanceKm(), poi.tags(),
                    poi.address(), poi.businessHours(), poi.telephone(), poi.source(), "merchant-placeholder");
            options.add(new ActionCard.ActionOption("context-" + poi.poiId(), poi.name(),
                    candidateDescription(poi), "SUBMIT_PATCH", null, null, patch, List.of(poi.poiId()), preview));
            items.add(new CandidateItem(index, poi, patch));
            index++;
        }
        String candidateSetId = runtime.getCandidateIdPrefix() + context.draft().planId() + "-"
                + UUID.randomUUID().toString().substring(0, 8);
        String type = selected.stream().findFirst().map(ScoredPoi::phase).orElse("ACTIVITY");
        CandidateSet set = new CandidateSet(candidateSetId, type, null, items, Instant.now());
        ActionCard card = new ActionCard("contextual-research-" + context.draft().planId(),
                searchPlan.title(), searchPlan.description(), options, null, false);
        return new CandidateCardResult(card, set);
    }

    private boolean blockedByAvoidTags(PoiDto poi, List<String> avoidTags) {
        String tags = String.join(" ", poi.tags()).toLowerCase(Locale.ROOT);
        for (String avoid : avoidTags) {
            if (avoid != null && !avoid.isBlank() && tags.contains(avoid.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private double score(PoiDto poi, ContextualResearchPlanner.SearchPlan searchPlan) {
        double score = 100 - poi.distanceKm() * 7;
        String tags = String.join(" ", poi.tags()).toLowerCase(Locale.ROOT);
        for (var entry : searchPlan.tagWeights().entrySet()) {
            String tag = entry.getKey() == null ? "" : entry.getKey().toLowerCase(Locale.ROOT);
            if (!tag.isBlank() && tags.contains(tag)) score += entry.getValue();
        }
        return score;
    }

    private PlanPatch addPatch(String phase, List<String> prefer) {
        return new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), dedupe(prefer), null, null, null, false),
                true);
    }

    private List<String> dedupe(List<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>(values == null ? List.of() : values);
        result.removeIf(value -> value == null || value.isBlank());
        return List.copyOf(result);
    }

    private String candidateDescription(PoiDto poi) {
        return poi.category() + " · " + String.format(Locale.ROOT, "%.1fkm", poi.distanceKm())
                + " · " + String.join(" / ", poi.tags());
    }

    private String extractTimeHint(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (text.contains("\u665a\u4e0a") || text.contains("\u4eca\u665a") || text.contains("tonight")) return "evening";
        if (text.contains("\u4e0b\u5348") || text.contains("afternoon")) return "afternoon";
        if (text.contains("\u4e2d\u5348") || text.contains("noon")) return "noon";
        if (text.contains("\u4e0a\u5348") || text.contains("morning")) return "morning";
        return null;
    }

    private String extractLocationHint(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (text.contains("\u9644\u8fd1") || text.contains("nearby")) return "nearby";
        if (text.contains("\u5546\u5708")) return "business_area";
        if (text.contains("\u5730\u94c1")) return "metro";
        return null;
    }

    private record ScoredPoi(PoiDto poi, String phase, double score) {
    }

    private String callModel(String systemPrompt, String userInput) {
        return chatModel.call(new Prompt(List.of(
                new SystemMessage(systemPrompt),
                new UserMessage(userInput == null ? "" : userInput)
        ))).getResult().getOutput().getContent();
    }

    private String consultSystemPrompt() {
        return """
                You are PlanPal, a conversational planning agent.
                For open-ended activity advice, chat first and do not search POIs.
                Return compact JSON only:
                {
                  "message": "中文自然建议。先比较几类方向，再问用户偏好。",
                  "choiceBar": {
                    "title": "偏好选择",
                    "description": "选择一个方向，我再继续帮你收窄。",
                    "options": [
                      {"label":"轻松低压力","description":"咖啡、散步、甜品，方便聊天","prompt":"PREFERENCE:relaxed_low_pressure"},
                      {"label":"有话题但不尴尬","description":"展览、电影、书店，有自然话题","prompt":"PREFERENCE:topic_safe"},
                      {"label":"有一点仪式感","description":"晚餐、清吧、夜景，氛围更明显","prompt":"PREFERENCE:ritual"},
                      {"label":"预算友好","description":"少排队、少花钱、节奏轻松","prompt":"PREFERENCE:budget_friendly"}
                    ]
                  }
                }
                Rules:
                - choiceBar.options must contain 2 to 5 options.
                - Options are preference directions only, not POIs, merchants, cinemas, restaurants, malls, addresses, or concrete places.
                - Each option must include label, description, and prompt.
                - Each prompt must start with PREFERENCE:.
                - Do not include poiPreview, planPatch, poiIds, distance, address, or merchant metadata.
                """;
    }

    private String repairSystemPrompt(String invalidRaw) {
        return """
                The previous JSON was invalid for PlanPal's preference choice bar.
                Return compact JSON only, no markdown:
                {
                  "message": "中文自然建议，一句话说明可以先选方向。",
                  "choiceBar": {
                    "title": "偏好选择",
                    "description": "选择一个方向，我再继续帮你收窄。",
                    "options": [
                      {"label":"轻松低压力","description":"咖啡、散步、甜品，方便聊天","prompt":"PREFERENCE:relaxed_low_pressure"},
                      {"label":"有话题但不尴尬","description":"展览、电影、书店，有自然话题","prompt":"PREFERENCE:topic_safe"},
                      {"label":"有一点仪式感","description":"晚餐、清吧、夜景，氛围更明显","prompt":"PREFERENCE:ritual"}
                    ]
                  }
                }
                Hard requirements:
                - options count must be 2 to 5.
                - no POIs, merchants, cinemas, restaurants, addresses, distances, poiPreview, poiIds, or planPatch.
                - every option prompt must start with PREFERENCE:.
                Invalid previous output:
                """ + (invalidRaw == null ? "" : invalidRaw);
    }

    private ConsultResult parseConsultJson(String raw, String planId) throws Exception {
        if (raw == null || raw.isBlank()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        JsonNode root = objectMapper.readTree(raw.substring(start, end + 1));
        String message = root.path("message").asText("");
        JsonNode choiceBar = root.path("choiceBar");
        if (!choiceBar.isObject()) return null;
        List<ChoiceBarTool.ChoiceBarOption> options = validateChoiceOptions(choiceBar.path("options"));
        if (options.isEmpty()) return null;
        ActionCard card = choiceBarTool.renderChoiceBar(new ChoiceBarTool.ChoiceBarSpec(
                "consult-choice-" + planId,
                choiceBar.path("title").asText("偏好选择"),
                choiceBar.path("description").asText("先选一个方向，我再继续帮你收窄。"),
                options,
                "也可以直接说你的偏好，比如安静一点、别太贵、别太尴尬",
                true));
        return new ConsultResult(message.isBlank() ? defaultConsultMessage() : message, card);
    }

    private List<ChoiceBarTool.ChoiceBarOption> validateChoiceOptions(JsonNode optionsNode) {
        if (!optionsNode.isArray()) return List.of();
        if (optionsNode.size() < MIN_CHOICE_OPTIONS || optionsNode.size() > MAX_CHOICE_OPTIONS) return List.of();

        List<ChoiceBarTool.ChoiceBarOption> options = new ArrayList<>();
        Set<String> labels = new HashSet<>();
        Set<String> prompts = new HashSet<>();
        for (JsonNode option : optionsNode) {
            if (hasForbiddenActionPayload(option)) return List.of();
            String label = text(option, "label");
            String description = text(option, "description");
            String prompt = text(option, "prompt");
            String actionType = text(option, "actionType");
            if (label.isBlank() || prompt.isBlank()) return List.of();
            if (!prompt.toUpperCase(Locale.ROOT).startsWith("PREFERENCE:")) return List.of();
            if (!actionType.isBlank() && !"SELECT_PREFERENCE".equalsIgnoreCase(actionType)) return List.of();
            if (looksLikeConcretePlace(label) || looksLikeConcretePlace(description) || looksLikeConcretePlace(prompt)) {
                return List.of();
            }
            String labelKey = normalizeKey(label);
            String promptKey = normalizeKey(prompt);
            if (!labels.add(labelKey) || !prompts.add(promptKey)) continue;
            options.add(new ChoiceBarTool.ChoiceBarOption(
                    text(option, "id"),
                    label,
                    description,
                    actionType.isBlank() ? "SELECT_PREFERENCE" : actionType,
                    prompt));
        }
        if (options.size() < MIN_CHOICE_OPTIONS || options.size() > MAX_CHOICE_OPTIONS) return List.of();
        return options;
    }

    private boolean hasForbiddenActionPayload(JsonNode option) {
        return hasNonEmpty(option, "poiPreview")
                || hasNonEmpty(option, "planPatch")
                || hasNonEmpty(option, "poiIds")
                || hasNonEmpty(option, "selectedPoiId");
    }

    private boolean hasNonEmpty(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) return false;
        if (value.isArray() || value.isObject()) return !value.isEmpty();
        return !value.asText("").isBlank();
    }

    private String text(JsonNode node, String field) {
        return node.path(field).asText("").trim();
    }

    private String normalizeKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private boolean looksLikeConcretePlace(String value) {
        String text = value == null ? "" : value.toLowerCase(Locale.ROOT);
        return text.contains("poi")
                || text.contains("km")
                || text.contains("公里")
                || text.contains("地址")
                || text.contains("营业")
                || text.contains("评分")
                || text.contains("商户")
                || text.contains("商家")
                || text.contains("餐厅")
                || text.contains("咖啡馆")
                || text.contains("影院")
                || text.contains("电影院")
                || text.contains("商场")
                || text.contains("中心");
    }

    private ConsultResult fallback(String planId) {
        String message = defaultConsultMessage();
        ActionCard card = choiceBarTool.renderChoiceBar(new ChoiceBarTool.ChoiceBarSpec(
                "consult-choice-" + planId,
                "先选约会方向",
                "选一个偏好，我会继续追问时间、地点和预算，再决定是否需要查具体场地。",
                List.of(
                        new ChoiceBarTool.ChoiceBarOption("pref-relaxed", "轻松低压力",
                                "咖啡、散步、甜品，重点是好聊天。", "SELECT_PREFERENCE", "PREFERENCE:relaxed_low_pressure"),
                        new ChoiceBarTool.ChoiceBarOption("pref-topic", "有话题但不尴尬",
                                "展览、电影、书店，适合自然接话。", "SELECT_PREFERENCE", "PREFERENCE:topic_safe"),
                        new ChoiceBarTool.ChoiceBarOption("pref-ritual", "有一点仪式感",
                                "晚餐、清吧、夜景，氛围更明显。", "SELECT_PREFERENCE", "PREFERENCE:ritual"),
                        new ChoiceBarTool.ChoiceBarOption("pref-budget", "预算友好",
                                "少排队、少花钱、节奏轻松。", "SELECT_PREFERENCE", "PREFERENCE:budget_friendly"),
                        new ChoiceBarTool.ChoiceBarOption("pref-rain", "下雨也合适",
                                "室内活动优先，降低天气风险。", "SELECT_PREFERENCE", "PREFERENCE:weather_safe")
                ),
                "也可以直接说：想安静点、别太贵、最好在某个商圈附近",
                true));
        return new ConsultResult(message, card);
    }

    private String defaultConsultMessage() {
        return "第一次约会可以先按互动强度来选：低压力的咖啡、散步、甜品，适合慢慢聊天；半互动的展览、电影，能自然制造话题；高互动的桌游、手作、Live 活动，会更有记忆点但也更挑双方状态。你更偏哪一种？";
    }

    private PlanIntent consultingIntent(PlanRequest request) {
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt());
        if (extracted == null) {
            extracted = new PlanIntent(1, List.of(), null, null, 0, "DATE",
                    List.of(), List.of(), null, null, request.prompt(), true);
        }
        return new PlanIntent(extracted.headcount(), extracted.participants(), null, null, 0,
                extracted.sceneType() == null ? "DATE" : extracted.sceneType(), extracted.requestedSegments(),
                extracted.dietaryConstraints(), extracted.drinkPreference(), extracted.locationScope(),
                request.prompt(), extracted.pace(), extracted.budgetLevel(), extracted.hasChildren(),
                extracted.childAge(), extracted.preferredTransportMode(), extracted.avoid(), extracted.mustHave(),
                extracted.weatherSensitive(), true);
    }

    private PlanResponse response(String planId, String userId, PlanIntent intent, String message) {
        return new PlanResponse(planId, userId, "SUCCESS", "", List.of(), List.of(), "", message,
                null, intent, List.of(), "PENDING_CONFIRMATION", 1, PlanStatus.PENDING_CONFIRMATION,
                List.of(), List.of(), null);
    }

    private String normalizePreference(String input) {
        String text = input == null ? "" : input.toLowerCase(Locale.ROOT);
        if (text.contains("topic") || text.contains("话题") || text.contains("不尴尬")) return "topic_safe";
        if (text.contains("ritual") || text.contains("仪式")) return "ritual";
        if (text.contains("budget") || text.contains("预算") || text.contains("便宜")) return "budget_friendly";
        if (text.contains("rain") || text.contains("下雨") || text.contains("室内")) return "weather_safe";
        return "relaxed_low_pressure";
    }

    private String interactionFor(String preference) {
        return switch (preference) {
            case "topic_safe" -> "medium";
            case "ritual" -> "low_to_medium";
            case "weather_safe" -> "medium";
            default -> "low";
        };
    }

    private String budgetFor(String preference) {
        return "budget_friendly".equals(preference) ? "controlled" : null;
    }

    private String weatherFor(String preference) {
        return "weather_safe".equals(preference) ? "indoor_first" : null;
    }

    private String nextQuestion(String preference) {
        String lead = switch (preference) {
            case "topic_safe" -> "这个方向挺适合第一次见面：有共同话题，又不会一直干聊。";
            case "ritual" -> "这个方向会更有氛围，但我会帮你控制节奏，避免太正式或太有压力。";
            case "budget_friendly" -> "可以，预算友好不等于随便，重点是路线顺、选择舒服。";
            case "weather_safe" -> "收到，那我会优先考虑室内、少走路、受天气影响小的安排。";
            default -> "这个方向很稳，低压力、好聊天，也方便随时调整。";
        };
        return lead + " 你们大概什么时候、在哪个区域见？如果还没定，我可以先继续帮你比较几个方向。";
    }

    private record ConsultResult(String message, ActionCard card) {
    }
}
