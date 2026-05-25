package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.tool.RestaurantReservationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class ConsultantEngine {

    private static final Logger log = LoggerFactory.getLogger(ConsultantEngine.class);

    private final ChatModel chatModel;
    private final PoiProvider poiDatabase;
    private final RestaurantReservationTool reservationTool;
    private final ReActEngine reactEngine;
    private final FastPlanEngine fastPlanEngine;
    private final ObjectMapper objectMapper;
    private final SearchTaskCompiler searchTaskCompiler;
    private final PlanningToolOrchestrator planningToolOrchestrator;

    public ConsultantEngine(ChatModel chatModel,
                            PoiProvider poiDatabase,
                            RestaurantReservationTool reservationTool,
                            ReActEngine reactEngine,
                            FastPlanEngine fastPlanEngine,
                            ObjectMapper objectMapper,
                            SearchTaskCompiler searchTaskCompiler,
                            PlanningToolOrchestrator planningToolOrchestrator) {
        this.chatModel = chatModel;
        this.poiDatabase = poiDatabase;
        this.reservationTool = reservationTool;
        this.reactEngine = reactEngine;
        this.fastPlanEngine = fastPlanEngine;
        this.objectMapper = objectMapper;
        this.searchTaskCompiler = searchTaskCompiler;
        this.planningToolOrchestrator = planningToolOrchestrator;
    }

    public void executeConsultStream(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent intent) {
        log.info("[ConsultantEngine] start consult prompt={}", request.prompt());
        String planId = request.planId() != null ? request.planId() : UUID.randomUUID().toString().substring(0, 8);

        List<String> tags = detectTags(request.prompt(), intent);
        List<SearchTask> tasks = searchTaskCompiler.compileConsulting(intent, tags);
        emitter.accept(new SseEvent("ACTION", 1,
                "PlanningToolOrchestrator.collectCandidates: tasks=" + tasks.size() + ", tags=" + tags,
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        CandidatePool pool = planningToolOrchestrator.collectCandidates(planId, intent, tasks);
        List<PoiDto> allCandidates = pool.phaseCandidates().values().stream()
                .flatMap(List::stream)
                .map(CandidateProfile::poi)
                .collect(LinkedHashMap<String, PoiDto>::new,
                        (map, poi) -> map.putIfAbsent(poi.poiId(), poi),
                        LinkedHashMap::putAll)
                .values().stream()
                .toList();

        long activityCount = allCandidates.stream().filter(poi -> "ACTIVITY".equalsIgnoreCase(poi.category())).count();
        long restaurantCount = allCandidates.stream().filter(poi -> "RESTAURANT".equalsIgnoreCase(poi.category())).count();
        emitter.accept(new SseEvent("OBSERVATION", 1,
                "candidatePool activity=" + activityCount + ", restaurant=" + restaurantCount
                        + ", stats=" + pool.taskStats().stream()
                        .map(stat -> stat.taskId() + ":" + stat.resultCount() + ":" + stat.elapsedMs() + "ms")
                        .toList(),
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        List<PoiDto> availablePois = new ArrayList<>();
        boolean allQueuedOrDegraded = true;
        emitter.accept(new SseEvent("ACTION", 2,
                "PlanningToolOrchestrator.checkAvailability: top candidates by phase",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        for (Map.Entry<String, List<CandidateProfile>> entry : pool.phaseCandidates().entrySet()) {
            AvailabilitySelection selection = planningToolOrchestrator.selectAvailable(
                    entry.getKey(), entry.getValue(), intent, intent.startTime(), Set.of());
            availablePois.addAll(selection.checkedCandidates().stream()
                    .filter(candidate -> candidate.rejectionReason() == null || candidate.rejectionReason().isBlank())
                    .map(CandidateProfile::poi)
                    .toList());
            if (selection.poi() != null) {
                allQueuedOrDegraded = false;
            }
        }

        emitter.accept(new SseEvent("OBSERVATION", 2,
                "availability available=" + availablePois.size() + ", degradation=" + pool.degradationNotes(),
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        if (allCandidates.isEmpty() || allQueuedOrDegraded) {
            log.warn("[ConsultantEngine] candidate pool unavailable, falling back to ReActEngine");
            emitter.accept(new SseEvent("START", 0,
                    "Candidate pool is empty or unavailable. Falling back to deeper ReAct search.",
                    List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
            reactEngine.executePlanStreaming(request, emitter, intent);
            return;
        }

        emitter.accept(new SseEvent("START", 0,
                "Starting consultant recommendation from verified candidate pool.",
                List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        String poiList = describePois(availablePois);
        String systemPrompt = """
                You are PlanPal's travel consultant. Recommend two practical options based only on the verified POI list below.

                Rules:
                - Do not invent POI ids or names.
                - Use [POI:poiId:poiName] when mentioning a place.
                - Give concise, structured recommendations.
                - End the response with exactly one <CHOICE_BAR> JSON block.
                - The JSON shape must be:
                  <CHOICE_BAR>
                  {
                    "title": "Choose an option to build a plan",
                    "description": "Pick one route idea and PlanPal will assemble the timeline.",
                    "options": [
                      {"label": "Option name", "description": "Why it works", "poiIds": ["P001"]},
                      {"label": "Option name", "description": "Why it works", "poiIds": ["P002"]}
                    ]
                  }
                  </CHOICE_BAR>

                Verified POIs:
                """ + poiList;

        StringBuilder textAccumulated = new StringBuilder();
        try {
            Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux = chatModel.stream(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(request.prompt())
            )));

            responseFlux.doOnNext(chatResponse -> {
                String chunk = chatResponse.getResult().getOutput().getContent();
                if (chunk == null) return;
                textAccumulated.append(chunk);

                String text = textAccumulated.toString();
                ParsedChoiceBar parsed = parseChoiceBar(text, planId);
                emitter.accept(new SseEvent("THOUGHT", 3, parsed.visibleContent().trim(), List.of(),
                        null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, parsed.choiceCard()));
            }).blockLast();

            ParsedChoiceBar finalParsed = parseChoiceBar(textAccumulated.toString(), planId);
            int recommendCount = countRecommendedPois(textAccumulated.toString(), availablePois);
            String summary = "Recommended " + recommendCount + " verified places for this outing.";
            emitter.accept(new SseEvent("FINISH", 4, summary, List.of(),
                    "SUCCESS", "", "", null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, finalParsed.choiceCard()));
        } catch (Exception e) {
            log.error("[ConsultantEngine] consultant stream failed", e);
            emitter.accept(new SseEvent("ERROR", 5,
                    "Consultant recommendation failed. Please try again.",
                    List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        }
    }

    private String describePois(List<PoiDto> pois) {
        StringBuilder builder = new StringBuilder();
        for (PoiDto poi : pois) {
            builder.append(String.format(Locale.ROOT,
                    "- [POI:%s:%s] category=%s distance=%.1fkm duration=%dmin tags=%s%n",
                    poi.poiId(), poi.name(), poi.category(), poi.distanceKm(),
                    poi.recommendedDurationMinutes(), String.join(", ", poi.tags())));
        }
        return builder.toString();
    }

    private ParsedChoiceBar parseChoiceBar(String text, String planId) {
        int startIndex = text.indexOf("<CHOICE_BAR>");
        int endIndex = text.indexOf("</CHOICE_BAR>");
        if (startIndex < 0) {
            return new ParsedChoiceBar(text, null);
        }
        if (endIndex <= startIndex) {
            return new ParsedChoiceBar(text.substring(0, startIndex), null);
        }

        ActionCard choiceCard = null;
        String json = text.substring(startIndex + "<CHOICE_BAR>".length(), endIndex).trim();
        try {
            ChoiceBarDto dto = objectMapper.readValue(json, ChoiceBarDto.class);
            List<ActionCard.ActionOption> options = new ArrayList<>();
            if (dto.options != null) {
                for (int i = 0; i < dto.options.size(); i++) {
                    ChoiceBarOptionOpt opt = dto.options.get(i);
                    options.add(new ActionCard.ActionOption(
                            "opt-" + i + "-" + UUID.randomUUID().toString().substring(0, 4),
                            opt.label,
                            opt.description != null ? opt.description : "",
                            "BUILD_PLAN",
                            null,
                            null,
                            null,
                            opt.poiIds != null ? opt.poiIds : List.of()
                    ));
                }
            }
            choiceCard = new ActionCard(
                    "choice-bar-" + planId,
                    dto.title != null ? dto.title : "Choose an option to build a plan",
                    dto.description != null ? dto.description : "Pick one option to assemble the route.",
                    options,
                    null,
                    false
            );
        } catch (Exception e) {
            log.warn("Failed to parse choice bar JSON: {}", e.toString());
        }
        String visible = text.substring(0, startIndex) + text.substring(endIndex + "</CHOICE_BAR>".length());
        return new ParsedChoiceBar(visible, choiceCard);
    }

    private int countRecommendedPois(String text, List<PoiDto> availablePois) {
        int count = 0;
        for (PoiDto poi : availablePois) {
            if (text.contains(poi.poiId())) {
                count++;
            }
        }
        return count == 0 ? Math.min(2, availablePois.size()) : count;
    }

    private List<String> detectTags(String prompt, PlanIntent intent) {
        List<String> tags = new ArrayList<>();
        if (intent != null && intent.sceneType() != null) {
            switch (intent.sceneType().toUpperCase(Locale.ROOT)) {
                case "DATE" -> tags.addAll(List.of("quiet_bar", "bar", "dessert", "movie", "photo", "social_dining", "exhibition"));
                case "FAMILY" -> tags.addAll(List.of("child_friendly", "outdoor", "indoor", "science", "sports", "free"));
                case "SOCIAL" -> tags.addAll(List.of("social_entertainment", "social_dining", "party", "club", "late_night", "livehouse"));
                case "SOLO" -> tags.addAll(List.of("solo_friendly", "quiet_bar", "coffee", "tea", "casual"));
                default -> tags.addAll(List.of("social_entertainment", "movie", "bar", "coffee", "dessert", "casual"));
            }
            if (intent.hasChildren()) {
                tags.addAll(List.of("child_friendly", "indoor", "science"));
            }
        }
        addPromptBasedTags(tags, prompt);
        return tags;
    }

    private void addPromptBasedTags(List<String> tags, String prompt) {
        String lower = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        if (lower.contains("date") || lower.contains("couple") || lower.contains("girlfriend") || lower.contains("boyfriend")) {
            tags.addAll(List.of("quiet_bar", "bar", "dessert", "movie", "photo", "social_dining", "exhibition"));
        }
        if (lower.contains("kid") || lower.contains("child") || lower.contains("family")) {
            tags.addAll(List.of("child_friendly", "outdoor", "indoor", "science", "sports", "free"));
        }
        if (lower.contains("club") || lower.contains("party") || lower.contains("livehouse")) {
            tags.addAll(List.of("club", "nightclub", "dance", "late_night", "livehouse", "party", "social_dining"));
        }
        if (lower.contains("spicy") || lower.contains("hotpot") || lower.contains("sichuan")) {
            tags.addAll(List.of("spicy", "sichuan", "hunan", "hotpot", "crayfish"));
        }
        if (lower.contains("healthy") || lower.contains("light") || lower.contains("vegan")) {
            tags.addAll(List.of("dietary_type=light", "healthy", "vegan", "quiet"));
        }
        if (lower.contains("dessert") || lower.contains("juice") || lower.contains("coffee") || lower.contains("tea")) {
            tags.addAll(List.of("smoothie", "dessert", "juice", "tea", "coffee"));
        }
        if (tags.isEmpty()) {
            tags.addAll(List.of("social_entertainment", "movie", "bar", "coffee", "dessert", "casual"));
        }
    }

    private record ParsedChoiceBar(String visibleContent, ActionCard choiceCard) {}

    public static class ChoiceBarDto {
        public String title;
        public String description;
        public List<ChoiceBarOptionOpt> options;
    }

    public static class ChoiceBarOptionOpt {
        public String label;
        public String description;
        public List<String> poiIds;
    }
}
