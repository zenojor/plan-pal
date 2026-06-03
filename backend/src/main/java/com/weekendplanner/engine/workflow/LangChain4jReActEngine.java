package com.weekendplanner.engine.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.engine.intent.IntentParser;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.exception.AgentPlanningException;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.LangChain4jTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

@Component
public class LangChain4jReActEngine {

    private static final Logger log = LoggerFactory.getLogger(LangChain4jReActEngine.class);
    private static final int MAX_MESSAGES = 50;

    private final OpenAiChatModel chatModel;
    private final IntentParser intentParser;
    private final MockPoiDatabase poiDatabase;
    private final PlanExecutionStore executionStore;
    private final ObjectMapper objectMapper;
    private final TimelineAssembler timelineAssembler;
    private final LangChain4jTools tools;
    private final List<ToolSpecification> toolSpecifications;

    @Value("${agent.max-steps:20}")
    private int maxSteps;

    @Value("${agent.max-replan-attempts:3}")
    private int maxReplanAttempts;

    @Value("${agent.default-radius-km:3}")
    private int defaultRadiusKm;

    @Value("${agent.max-radius-km:5}")
    private int maxRadiusKm;

    @Value("${agent.queue-threshold-minutes:30}")
    private int queueThresholdMinutes;

    @Value("classpath:prompts/system-prompt.txt")
    private Resource systemPromptResource;

    @Autowired
    public LangChain4jReActEngine(OpenAiChatModel langChain4jChatModel,
                                   IntentParser intentParser,
                                   MockPoiDatabase poiDatabase,
                                   PlanExecutionStore executionStore,
                                   ObjectMapper objectMapper,
                                   TimelineAssembler timelineAssembler,
                                   LangChain4jTools tools) {
        this.chatModel = langChain4jChatModel;
        this.intentParser = intentParser;
        this.poiDatabase = poiDatabase;
        this.executionStore = executionStore;
        this.objectMapper = objectMapper;
        this.timelineAssembler = timelineAssembler;
        this.tools = tools;
        this.toolSpecifications = buildToolSpecifications();
    }

    private List<ToolSpecification> buildToolSpecifications() {
        List<ToolSpecification> specs = new ArrayList<>();
        for (Method method : LangChain4jTools.class.getDeclaredMethods()) {
            dev.langchain4j.agent.tool.Tool toolAnn = method.getAnnotation(dev.langchain4j.agent.tool.Tool.class);
            if (toolAnn != null) {
                specs.add(ToolSpecifications.toolSpecificationFrom(method));
            }
        }
        log.info("[LangChain4jReAct] Built {} tool specifications", specs.size());
        return specs;
    }

    // ==================== Public API (same as old ReActEngine) ====================

    public PlanResponse executePlan(PlanRequest request) {
        return executePlanInternal(request, null, null);
    }

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return executePlanInternal(request, emitter, null);
    }

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent intent) {
        return executePlanInternal(request, emitter, intent);
    }

    // ==================== Core loop ====================

    private PlanResponse executePlanInternal(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent intent) {
        String planId = request.planId() != null ? request.planId() : UUID.randomUUID().toString().substring(0, 8);

        UserProfile profile;
        if (request.planId() != null) {
            Optional<PlanExecutionStore.DraftPlan> originalOpt = executionStore.find(request.planId());
            if (originalOpt.isPresent()) {
                PlanIntent originalIntent = originalOpt.get().intent();
                UserProfile newProfile = intentParser.parse(request.prompt());
                profile = mergeProfiles(originalIntent, newProfile);
            } else {
                profile = intentParser.parse(request.prompt());
            }
        } else {
            profile = intentParser.parse(request.prompt());
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(buildSystemPrompt(profile)));
        messages.add(UserMessage.from(request.prompt()));

        log.info("[LangChain4jReAct] start planId={}, scene={}", planId, profile.isSocialScene() ? "SOCIAL" : "FAMILY");
        emit(emitter, new SseEvent("START", 0, "开始规划...", null, null, null, null, null, planId, intent, null, null));

        int step = 0;
        int consecutiveThoughts = 0;
        int replanCount = 0;
        int currentRadiusKm = defaultRadiusKm;
        String finalSummary = null;
        String finalNotificationText = null;
        List<PlanStep> finalTimeline = List.of();
        List<PoiDto> selectedPois = new ArrayList<>();
        Set<String> dedupKeys = new HashSet<>();
        List<ReActTrace> trace = new ArrayList<>();
        boolean degraded = false;
        String degradationNote = null;

        while (step < maxSteps) {
            step++;

            if (consecutiveThoughts >= 3) {
                messages.add(SystemMessage.from(
                        "[系统提醒] 已连续" + consecutiveThoughts + "次分析没有行动。请立刻选择当前最优 POI，调用 checkAvailability 或准备 FINISH 输出方案。"));
                consecutiveThoughts = 0;
            }

            ChatRequest chatRequest = ChatRequest.builder()
                    .messages(trimMessages(messages))
                    .toolSpecifications(toolSpecifications)
                    .build();

            ChatResponse chatResponse;
            try {
                chatResponse = chatModel.chat(chatRequest);
            } catch (Exception e) {
                log.error("[LangChain4jReAct:{}] LLM call failed", step, e);
                continue;
            }

            AiMessage aiMessage = chatResponse.aiMessage();
            messages.add(aiMessage);

            // Has tool calls → execute them
            if (aiMessage.hasToolExecutionRequests()) {
                consecutiveThoughts = 0;
                for (ToolExecutionRequest toolRequest : aiMessage.toolExecutionRequests()) {
                    String toolName = toolRequest.name();
                    String arguments = toolRequest.arguments();

                    trace.add(new ReActTrace(step, "ACTION",
                            "Tool: " + toolName + ", Params: " + truncate(arguments, 300)));
                    emitAction(emitter, step, toolName + ": " + truncate(arguments, 200), planId, intent);

                    // Dedup
                    String dedupKey = toolName + ":" + arguments;
                    if (dedupKeys.contains(dedupKey)) {
                        messages.add(ToolExecutionResultMessage.from(toolRequest,
                                "{\"warning\":\"已执行过相同调用，请换参数或选其他POI\"}"));
                        continue;
                    }
                    dedupKeys.add(dedupKey);

                    // Execute
                    String resultJson;
                    try {
                        resultJson = executeToolByName(toolName, arguments);
                    } catch (Exception e) {
                        resultJson = "{\"error\":\"" + e.getMessage() + "\"}";
                    }

                    trace.add(new ReActTrace(step, "OBSERVATION", truncate(resultJson, 500)));
                    emitObservation(emitter, step, truncate(resultJson, 300), planId, intent);

                    messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson));

                    // Track POIs from search results
                    if ("searchNearby".equals(toolName)) {
                        trackSelectedPois(resultJson, selectedPois);
                    }

                    // Replan detection
                    if ("checkAvailability".equals(toolName) && needsReplan(resultJson)) {
                        replanCount++;
                        log.warn("[LangChain4jReAct] replan trigger count={}", replanCount);
                        handleDegradation(messages, replanCount, currentRadiusKm);
                        if (replanCount > maxReplanAttempts) {
                            degraded = true;
                            degradationNote = "已降级：多次尝试后放宽约束";
                        }
                    }
                }
                continue;
            }

            // Text-only response → check for FINISH
            String text = aiMessage.text();
            if (text == null || text.isBlank()) {
                consecutiveThoughts++;
                continue;
            }

            JsonNode parsed = parseFinishJson(text);
            if (parsed != null && parsed.has("summary")) {
                finalSummary = parsed.path("summary").asText(text);
                finalNotificationText = parsed.path("notificationText").asText(finalSummary);
                if (parsed.has("degradationNote") && !parsed.get("degradationNote").isNull()) {
                    degradationNote = parsed.path("degradationNote").asText();
                }
                finalTimeline = parseTimeline(parsed, selectedPois);
                trace.add(new ReActTrace(step, "FINISH", finalSummary));
                log.info("[LangChain4jReAct] plan complete planId={}, steps={}", planId, step);
                break;
            }

            // Not FINISH → treat as thought
            trace.add(new ReActTrace(step, "THOUGHT", truncate(text, 500)));
            emitThought(emitter, step, truncate(text, 300), planId, intent);
            consecutiveThoughts++;
        }

        // Fallback: no FINISH → build from selected POIs
        if (finalSummary == null && finalTimeline.isEmpty()) {
            if (!selectedPois.isEmpty()) {
                List<PlanStep> fallbackSteps = new ArrayList<>();
                for (PoiDto p : selectedPois) {
                    String phase = "RESTAURANT".equals(p.category()) ? "DINING" : "ACTIVITY";
                    fallbackSteps.add(new PlanStep(p.recommendedDurationMinutes(), phase, "", p.poiId(), p.name(),
                            "", "", new double[]{p.lng(), p.lat()}, "", "", ""));
                }
                finalTimeline = timelineAssembler.ensureSegmentIds(planId, fallbackSteps);
                finalSummary = "已根据搜索结果生成方案。";
                degraded = true;
                degradationNote = degradationNote == null
                        ? "Agent reasoning did not produce a FINISH; fallback timeline assembled."
                        : degradationNote;
            } else {
                throw new AgentPlanningException("规划迭代超出最大步数上限(" + maxSteps + ")，触发安全熔断");
            }
        }

        finalTimeline = timelineAssembler.ensureSegmentIds(planId, finalTimeline);
        String orderGroupId = "G" + (700 + planId.hashCode() % 100);

        PlanResponse response = new PlanResponse(planId, request.userId(),
                degraded ? "DEGRADED" : "SUCCESS",
                finalSummary, finalTimeline, trace,
                orderGroupId, finalNotificationText != null ? finalNotificationText : finalSummary,
                degradationNote);

        emit(emitter, new SseEvent("FINISH", 999, response.summary(), response.timeline()));
        return response;
    }

    // ==================== Memory management ====================

    private List<ChatMessage> trimMessages(List<ChatMessage> messages) {
        if (messages.size() <= MAX_MESSAGES) return messages;
        // Keep first 2 (system + user) and last MAX_MESSAGES-2
        List<ChatMessage> trimmed = new ArrayList<>();
        trimmed.addAll(messages.subList(0, 2));
        trimmed.addAll(messages.subList(messages.size() - (MAX_MESSAGES - 2), messages.size()));
        return trimmed;
    }

    // ==================== Tool execution ====================

    private String executeToolByName(String toolName, String arguments) {
        try {
            JsonNode argsNode = objectMapper.readTree(arguments);
            return switch (toolName) {
                case "searchNearby" -> {
                    String category = argsNode.has("category") ? argsNode.get("category").asText() : "ACTIVITY";
                    List<String> tags = new ArrayList<>();
                    if (argsNode.has("tags") && argsNode.get("tags").isArray()) {
                        argsNode.get("tags").forEach(t -> tags.add(t.asText()));
                    }
                    int radius = argsNode.has("radiusKm") ? argsNode.get("radiusKm").asInt(3) : 3;
                    yield tools.searchNearby(category, tags, radius);
                }
                case "checkAvailability" -> {
                    String poiId = argsNode.path("poiId").asText("");
                    String targetTime = argsNode.path("targetTime").asText("14:00");
                    int headcount = argsNode.path("headcount").asInt(1);
                    yield tools.checkAvailability(poiId, targetTime, headcount);
                }
                case "reserveRestaurant" -> {
                    String poiId = argsNode.path("poiId").asText("");
                    int headcount = argsNode.path("headcount").asInt(1);
                    String targetTime = argsNode.path("targetTime").asText("18:00");
                    yield tools.reserveRestaurant(poiId, headcount, targetTime);
                }
                case "bookTickets" -> {
                    String poiId = argsNode.path("poiId").asText("");
                    int num = argsNode.path("num").asInt(1);
                    String sessionTime = argsNode.path("sessionTime").asText("14:30");
                    yield tools.bookTickets(poiId, num, sessionTime);
                }
                case "searchMovies" -> {
                    String cinemaId = argsNode.path("cinemaId").asText("");
                    String genre = argsNode.path("genre").asText("");
                    String keyword = argsNode.path("keyword").asText("");
                    String afterTime = argsNode.path("afterTime").asText("");
                    yield tools.searchMovies(cinemaId, genre, keyword, afterTime);
                }
                case "executeOrderAndNotify" -> {
                    List<String> orderIds = new ArrayList<>();
                    if (argsNode.has("orderIds") && argsNode.get("orderIds").isArray()) {
                        argsNode.get("orderIds").forEach(id -> orderIds.add(id.asText()));
                    }
                    String contactToken = argsNode.path("contactToken").asText("user");
                    yield tools.executeOrderAndNotify(orderIds, contactToken);
                }
                default -> "{\"error\":\"Unknown tool: " + toolName + "\"}";
            };
        } catch (Exception e) {
            log.error("[LangChain4jReAct] tool execution failed: {}", toolName, e);
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    // ==================== JSON parsing (for FINISH detection) ====================

    private JsonNode parseFinishJson(String text) {
        String trimmed = text.trim()
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "")
                .trim();
        int start = trimmed.indexOf('{');
        if (start == -1) return null;
        int depth = 0, end = -1;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
        }
        if (end == -1) return null;
        try {
            JsonNode node = objectMapper.readTree(trimmed.substring(start, end + 1));
            return node.has("summary") || node.has("timeline") ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    // ==================== POI tracking ====================

    private void trackSelectedPois(String resultJson, List<PoiDto> selectedPois) {
        try {
            JsonNode results = objectMapper.readTree(resultJson).path("results");
            if (results.isArray()) {
                for (JsonNode r : results) {
                    String id = r.path("poiId").asText();
                    if (!id.isEmpty() && selectedPois.stream().noneMatch(p -> p.poiId().equals(id))) {
                        poiDatabase.findById(id).ifPresent(selectedPois::add);
                    }
                }
            }
        } catch (JsonProcessingException ignored) {}
    }

    // ==================== Timeline parsing ====================

    private List<PlanStep> parseTimeline(JsonNode finish, List<PoiDto> selectedPois) {
        List<PlanStep> steps = new ArrayList<>();
        JsonNode timeline = finish.path("timeline");
        if (!timeline.isArray()) return steps;

        for (JsonNode n : timeline) {
            String poiId = n.path("poiId").asText("");
            String phase = n.path("phase").asText("");
            if ("TRANSITION".equalsIgnoreCase(phase) || "TRANSPORT".equalsIgnoreCase(phase)) continue;
            if (poiId.isEmpty() && phase.isEmpty()) continue;

            double[] lnglat = resolveLnglat(poiId, selectedPois);
            int duration = n.path("durationMinutes").asInt(60);

            steps.add(new PlanStep(duration, n.path("phase").asText(""), n.path("action").asText(""),
                    poiId, n.path("poiName").asText(""), n.path("bookingStatus").asText(""),
                    n.path("note").asText(""), lnglat, n.path("audience").asText(""),
                    n.path("reason").asText(""), n.path("budget").asText("")));
        }
        return steps;
    }

    private double[] resolveLnglat(String poiId, List<PoiDto> selectedPois) {
        if (poiId.isEmpty()) return null;
        Optional<PoiDto> match = selectedPois.stream().filter(p -> p.poiId().equals(poiId)).findFirst();
        if (match.isEmpty()) match = poiDatabase.findById(poiId);
        return match.map(p -> new double[]{p.lng(), p.lat()}).orElse(null);
    }

    // ==================== Replan / degradation ====================

    private boolean needsReplan(String resultJson) {
        try {
            JsonNode r = objectMapper.readTree(resultJson);
            return "SOLD_OUT".equals(r.path("status").asText())
                    || r.path("queueTimeMinutes").asInt(0) > queueThresholdMinutes;
        } catch (JsonProcessingException e) { return false; }
    }

    private void handleDegradation(List<ChatMessage> messages, int replanCount, int currentRadiusKm) {
        if (replanCount <= 2) {
            int newRadius = Math.min(currentRadiusKm + 1, maxRadiusKm);
            messages.add(SystemMessage.from("[降级 L" + replanCount + "] 搜索范围扩大到 " + newRadius + "km，请重新 searchNearby 使用更大的 radiusKm。"));
        } else if (replanCount <= 3) {
            messages.add(SystemMessage.from("[降级 L" + replanCount + "] 尝试颠倒活动与餐饮时间顺序，或调整时间段。"));
        } else {
            messages.add(SystemMessage.from("[降级] 放宽轻食/健康约束，从普通餐厅中选择，并在 summary 中标注降级原因。"));
        }
    }

    // ==================== System prompt ====================

    private String buildSystemPrompt(UserProfile profile) {
        String base = loadSystemPrompt();
        StringBuilder sb = new StringBuilder(base);
        sb.append("\n\n## 当前场景\n\n");
        if (profile.isSocialScene()) {
            sb.append("- 场景: 朋友社交，").append(profile.headcount()).append("人\n");
            sb.append("- 活动标签: social_entertainment，餐饮标签: social_dining\n");
        } else {
            sb.append("- 场景: 家庭休闲，").append(profile.headcount()).append("人");
            if (profile.childCount() > 0) sb.append("（含").append(profile.childCount()).append("名儿童）");
            sb.append("\n- 活动标签: child_friendly，餐饮标签: dietary_type=light/healthy\n");
            sb.append("- 排除: adult_only, 密室逃脱\n");
        }
        sb.append("- 时间: ").append(profile.startTime()).append("起，").append(profile.preferredHours()).append("小时\n");
        sb.append("- 距离: ").append(profile.maxRadiusKm()).append("km 以内\n");
        return sb.toString();
    }

    private String loadSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "你是一个本地周末活动规划 Agent。根据用户需求，使用提供的工具搜索 POI、检查可用性，最终输出一个包含 timeline 的方案。";
        }
    }

    // ==================== Profile merging ====================

    private UserProfile mergeProfiles(PlanIntent original, UserProfile newProfile) {
        int headcount = newProfile.headcount() > 0 && !newProfile.originalPrompt().equals(original.originalPrompt())
                ? newProfile.headcount() : original.headcount();

        int childCount = headcount > 1 && original.participants() != null
                ? (int) original.participants().stream().filter(p -> p.contains("孩子") || p.contains("儿童") || p.contains("娃")).count()
                : 0;

        boolean hasDietConstraint = newProfile.hasDietConstraint()
                || (original.dietaryConstraints() != null && !original.dietaryConstraints().isEmpty());
        String dietaryType = hasDietConstraint ? "light/healthy" : "normal";

        boolean isSocialScene = "SOCIAL".equalsIgnoreCase(original.sceneType());

        boolean hasTimeKeyword = contains(newProfile.originalPrompt(),
                "点", "时间", "时", "分", "延", "改", "HH:mm");
        String startTime = hasTimeKeyword ? newProfile.startTime() : original.startTime();

        int preferredHours = hasTimeKeyword
                ? newProfile.preferredHours()
                : (original.totalMinutes() > 0 ? original.totalMinutes() / 60 : 5);

        return new UserProfile(headcount, childCount, hasDietConstraint, dietaryType,
                isSocialScene, startTime, preferredHours, newProfile.maxRadiusKm(), newProfile.originalPrompt());
    }

    // ==================== Helpers ====================

    private void emit(Consumer<SseEvent> emitter, SseEvent event) {
        if (emitter != null) emitter.accept(event);
    }

    private void emitThought(Consumer<SseEvent> emitter, int step, String content, String planId, PlanIntent intent) {
        if (emitter != null)
            emitter.accept(new SseEvent("THOUGHT", step, content, null,
                    null, null, null, null, planId, intent, null, null));
    }

    private void emitAction(Consumer<SseEvent> emitter, int step, String content, String planId, PlanIntent intent) {
        if (emitter != null)
            emitter.accept(new SseEvent("ACTION", step, content, null,
                    null, null, null, null, planId, intent, null, null));
    }

    private void emitObservation(Consumer<SseEvent> emitter, int step, String content, String planId, PlanIntent intent) {
        if (emitter != null)
            emitter.accept(new SseEvent("OBSERVATION", step, content, null,
                    null, null, null, null, planId, intent, null, null));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private boolean contains(String text, String... keywords) {
        if (text == null) return false;
        for (String kw : keywords) {
            if (text.toLowerCase().contains(kw.toLowerCase())) return true;
        }
        return false;
    }
}
