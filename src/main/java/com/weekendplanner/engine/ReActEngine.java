package com.weekendplanner.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.exception.AgentPlanningException;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;

@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final IntentParser intentParser;
    private final MockPoiDatabase poiDatabase;
    private final ObjectMapper objectMapper;

    @Value("${agent.max-steps:15}")
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

    public ReActEngine(ChatModel chatModel, ToolRegistry toolRegistry,
                       IntentParser intentParser, MockPoiDatabase poiDatabase,
                       ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.intentParser = intentParser;
        this.poiDatabase = poiDatabase;
        this.objectMapper = objectMapper;
    }

    // ==================== 同步规划 ====================

    public PlanResponse executePlan(PlanRequest request) {
        return executePlanInternal(request, null);
    }

    // ==================== SSE 流式规划 ====================

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return executePlanInternal(request, emitter);
    }

    // ==================== 内部实现 ====================

    private PlanResponse executePlanInternal(PlanRequest request, Consumer<SseEvent> emitter) {
        String planId = UUID.randomUUID().toString().substring(0, 8);

        UserProfile profile = intentParser.parse(request.prompt());
        ContextLedger ledger = new ContextLedger(defaultRadiusKm);

        String systemPrompt = buildSystemPrompt(profile);
        ledger.addSystem(systemPrompt);
        ledger.addUser(request.prompt());

        log.info("[ReAct] 开始 planId={}, scene={}", planId, profile.isSocialScene() ? "SOCIAL" : "FAMILY");
        emit(emitter, new SseEvent("START", 0, "开始规划...", null));

        // ReAct 主循环
        int step = 0;
        String finalOutput = null;
        List<PoiDto> selectedPois = new ArrayList<>();

        while (step < maxSteps) {
            step++;
            String aiOutput = callLlm(ledger);
            ledger.addAssistant(aiOutput);

            JsonNode parsed = parseJsonOutput(aiOutput);
            if (parsed == null) {
                log.warn("[ReAct:{}] 无法解析 JSON，跳过此轮", step);
                ledger.traceThought(aiOutput);
                emit(emitter, new SseEvent("THOUGHT", step, truncate(aiOutput, 300), null));
                continue;
            }

            String action = parsed.has("action") ? parsed.get("action").asText() : "";

            if ("THOUGHT".equalsIgnoreCase(action)) {
                String thought = parsed.has("thought") ? parsed.get("thought").asText() : "";
                ledger.traceThought(thought);
                emit(emitter, new SseEvent("THOUGHT", step, truncate(thought, 300), null));
                continue;
            }

            if ("FINISH".equalsIgnoreCase(action)) {
                ledger.traceFinish(parsed.toString());
                finalOutput = parsed.toString();
                log.info("[ReAct] 规划完成 planId={}, 共{}步", planId, step);
                break;
            }

            // 执行工具调用
            ToolCallResult toolResult = executeToolCall(parsed, ledger);
            ledger.addObservation(toolResult.resultJson());
            emit(emitter, new SseEvent("ACTION", step, action + ": " + truncate(parsed.toString(), 200), null));

            // 跟踪选中的 POI
            trackSelectedPois(action, toolResult.resultJson(), selectedPois);
        }

        if (finalOutput == null) {
            throw new AgentPlanningException("规划迭代超出最大步数上限(" + maxSteps + ")，触发安全熔断");
        }

        // 用格式化 LLM 调用产出结构化 FINISH（如果 LLM 的 FINISH 已经结构化则直接用）
        PlanResponse response = buildResponse(planId, request.userId(), finalOutput, ledger, selectedPois);
        emit(emitter, new SseEvent("FINISH", 999, response.summary(), response.timeline()));
        return response;
    }

    // ==================== LLM 调用 ====================

    private String callLlm(ContextLedger ledger) {
        List<Message> messages = ledger.getMessages();
        Prompt prompt = new Prompt(messages);
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getContent();
    }

    // ==================== JSON 解析 ====================

    /**
     * 从 LLM 输出中提取完整 JSON 对象（brace-matching 方式）
     */
    private JsonNode parseJsonOutput(String aiOutput) {
        String trimmed = aiOutput.trim();

        // 去掉 markdown 代码块包裹
        trimmed = trimmed.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

        // 找到第一个 { 和匹配的 }
        int start = trimmed.indexOf('{');
        if (start == -1) return tryParseFull(trimmed);

        int depth = 0;
        int end = -1;
        for (int i = start; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) { end = i; break; } }
        }
        if (end == -1) return null;

        String jsonStr = trimmed.substring(start, end + 1);
        return tryParse(jsonStr);
    }

    private JsonNode tryParseFull(String s) {
        try {
            JsonNode node = objectMapper.readTree(s);
            return node.has("action") ? node : null;
        } catch (JsonProcessingException e) { return null; }
    }

    private JsonNode tryParse(String s) {
        try {
            JsonNode node = objectMapper.readTree(s);
            if (node.has("action")) return node;
        } catch (JsonProcessingException ignored) {}
        return null;
    }

    // ==================== 工具执行 ====================

    private ToolCallResult executeToolCall(JsonNode parsed, ContextLedger ledger) {
        String action = parsed.get("action").asText();
        String parameters = extractParameters(parsed, action);

        ledger.traceAction(action, parameters);
        ToolCallResult result = toolRegistry.execute(action, parameters);

        if (result.success() && needsReplan(action, result.resultJson())) {
            ledger.incrementReplan();
            log.warn("[ReAct] 触发重规划: action={}, count={}", action, ledger.getReplanCount());
            if (ledger.getReplanCount() > maxReplanAttempts) {
                handleDegradation(ledger);
            }
        }
        return result;
    }

    /**
     * 从 LLM 输出的 JSON 中提取工具参数。
     * 两种格式兼容:
     * 1. { "action": "checkAvailability", "parameters": { "poiId": "P001" } }
     * 2. { "action": "checkAvailability", "poiId": "P001", "targetTime": "14:00" }
     */
    private String extractParameters(JsonNode parsed, String action) {
        if (parsed.has("parameters") && parsed.get("parameters").isObject()) {
            return parsed.get("parameters").toString();
        }
        // 提取根级别字段 (排除 action / thought / summary / notificationText 等元数据)
        java.util.Map<String, Object> params = new java.util.LinkedHashMap<>();
        java.util.Set<String> metaKeys = java.util.Set.of("action", "thought", "summary",
                "notificationText", "degradationNote", "timeline");
        parsed.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            if (!metaKeys.contains(key)) {
                try {
                    params.put(key, objectMapper.treeToValue(entry.getValue(), Object.class));
                } catch (JsonProcessingException ignored) {}
            }
        });
        return params.isEmpty() ? "{}" : objectMapper.valueToTree(params).toString();
    }

    private boolean needsReplan(String toolName, String resultJson) {
        if (!"checkAvailability".equals(toolName)) return false;
        try {
            JsonNode r = objectMapper.readTree(resultJson);
            return "SOLD_OUT".equals(r.path("status").asText())
                    || r.path("queueTimeMinutes").asInt() > queueThresholdMinutes;
        } catch (JsonProcessingException e) { return false; }
    }

    private void handleDegradation(ContextLedger ledger) {
        int cnt = ledger.getReplanCount();
        if (cnt <= 2) {
            int r = Math.min(ledger.getCurrentRadiusKm() + 1, maxRadiusKm);
            ledger.expandRadius(r);
            ledger.addSystem("[降级 L1] 扩大搜索半径至 " + r + "km");
        } else if (cnt <= 3) {
            ledger.addSystem("[降级 L2] 颠倒活动与餐饮顺序或调整时间");
        } else {
            ledger.markDegraded("已降级选择普通餐饮，建议点餐时嘱托少油少盐");
            ledger.addSystem("[降级 L3] 放宽轻食约束，从普通中餐厅选择");
        }
    }

    // ==================== POI 跟踪 ====================

    private void trackSelectedPois(String action, String resultJson, List<PoiDto> selectedPois) {
        try {
            if ("searchNearby".equals(action)) {
                JsonNode results = objectMapper.readTree(resultJson).path("results");
                if (results.isArray()) {
                    for (JsonNode r : results) {
                        String id = r.path("poiId").asText();
                        if (!id.isEmpty() && selectedPois.stream().noneMatch(p -> p.poiId().equals(id))) {
                            poiDatabase.findById(id).ifPresent(selectedPois::add);
                        }
                    }
                }
            }
        } catch (JsonProcessingException ignored) {}
    }

    // ==================== 响应构建 ====================

    private PlanResponse buildResponse(String planId, String userId, String finalOutput,
                                        ContextLedger ledger, List<PoiDto> selectedPois) {
        try {
            JsonNode finish = objectMapper.readTree(finalOutput);
            String summary = finish.path("summary").asText("");
            String notificationText = finish.path("notificationText").asText(summary);
            String degradationNote = finish.has("degradationNote") && !finish.get("degradationNote").isNull()
                    ? finish.get("degradationNote").asText() : ledger.getDegradationNote();

            List<PlanStep> timeline = parseTimeline(finish, selectedPois);
            String orderGroupId = "G" + (700 + planId.hashCode() % 100);

            return new PlanResponse(planId, userId,
                    ledger.isDegraded() ? "DEGRADED" : "SUCCESS",
                    summary, timeline, ledger.getTrace(),
                    orderGroupId, notificationText, degradationNote);
        } catch (JsonProcessingException e) {
            // fallback: LLM FINISH 不是有效 JSON
            return buildFallbackResponse(planId, userId, finalOutput, ledger, selectedPois);
        }
    }

    /**
     * 从 FINISH JSON 中解析 timeline，并用 MockPoiDatabase 补全 lnglat
     */
    private List<PlanStep> parseTimeline(JsonNode finish, List<PoiDto> selectedPois) {
        List<PlanStep> steps = new ArrayList<>();
        JsonNode timeline = finish.path("timeline");
        if (!timeline.isArray()) return steps;

        for (JsonNode n : timeline) {
            String poiId = n.path("poiId").asText("");
            // 跳过交通/中转步骤（前端自行计算）
            String phase = n.path("phase").asText("");
            if ("TRANSITION".equalsIgnoreCase(phase) || "TRANSPORT".equalsIgnoreCase(phase)) continue;
            if (poiId.isEmpty() && phase.isEmpty()) continue;

            double[] lnglat = resolveLnglat(poiId, selectedPois);
            int duration = n.path("durationMinutes").asInt(60);

            steps.add(new PlanStep(
                    duration,
                    n.path("phase").asText(""),
                    n.path("action").asText(""),
                    poiId,
                    n.path("poiName").asText(""),
                    n.path("bookingStatus").asText(""),
                    n.path("note").asText(""),
                    lnglat,
                    n.path("audience").asText(""),
                    n.path("reason").asText(""),
                    n.path("budget").asText("")
            ));
        }
        return steps;
    }

    private double[] resolveLnglat(String poiId, List<PoiDto> selectedPois) {
        if (poiId.isEmpty()) return null;
        // 先从已选 POI 中找
        Optional<PoiDto> match = selectedPois.stream().filter(p -> p.poiId().equals(poiId)).findFirst();
        // 找不到就去全局库找
        if (match.isEmpty()) match = poiDatabase.findById(poiId);
        return match.map(p -> new double[]{p.lng(), p.lat()}).orElse(null);
    }

    private PlanResponse buildFallbackResponse(String planId, String userId, String summary,
                                                ContextLedger ledger, List<PoiDto> selectedPois) {
        List<PlanStep> timeline = new ArrayList<>();
        if (!selectedPois.isEmpty()) {
            for (PoiDto p : selectedPois) {
                String phase = "RESTAURANT".equals(p.category()) ? "DINING" : "ACTIVITY";
                timeline.add(new PlanStep(p.recommendedDurationMinutes(), phase, "", p.poiId(), p.name(), "", "",
                        new double[]{p.lng(), p.lat()}, "", "", ""));
            }
        }
        return new PlanResponse(planId, userId,
                ledger.isDegraded() ? "DEGRADED" : "SUCCESS",
                summary, timeline, ledger.getTrace(),
                "G" + (700 + planId.hashCode() % 100),
                summary, ledger.getDegradationNote());
    }

    // ==================== System Prompt ====================

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
        try { return systemPromptResource.getContentAsString(StandardCharsets.UTF_8); }
        catch (IOException e) { return "你是一个本地周末活动规划智能体。"; }
    }

    // ==================== 工具方法 ====================

    private void emit(Consumer<SseEvent> emitter, SseEvent event) {
        if (emitter != null) emitter.accept(event);
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
