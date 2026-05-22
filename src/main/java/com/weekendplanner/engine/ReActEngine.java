package com.weekendplanner.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.exception.AgentPlanningException;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReAct 核心引擎
 *
 * 自主实现 Thought → Action → Observation 状态机循环。
 * 按照设计文档的伪代码逻辑，提供确定性后端控制流包裹 LLM 推理。
 */
@Component
public class ReActEngine {

    private static final Logger log = LoggerFactory.getLogger(ReActEngine.class);

    private final ChatModel chatModel;
    private final ToolRegistry toolRegistry;
    private final IntentParser intentParser;
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

    @Value("${agent.time-budget-hours:6}")
    private int timeBudgetHours;

    @Value("classpath:prompts/system-prompt.txt")
    private Resource systemPromptResource;

    // 匹配 LLM 输出的 JSON 工具调用
    private static final Pattern JSON_BLOCK = Pattern.compile(
            "\\{[\\s\\S]*?\"action\"[\\s\\S]*?\\}",
            Pattern.DOTALL);

    public ReActEngine(ChatModel chatModel, ToolRegistry toolRegistry,
                       IntentParser intentParser, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.toolRegistry = toolRegistry;
        this.intentParser = intentParser;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行完整的 ReAct 规划流程
     */
    public PlanResponse executePlan(PlanRequest request) {
        String planId = UUID.randomUUID().toString().substring(0, 8);

        // 1. 意图解析
        UserProfile profile = intentParser.parse(request.prompt());

        // 2. 初始化上下文账本
        ContextLedger ledger = new ContextLedger(defaultRadiusKm);

        // 3. 注入 System Prompt
        String systemPrompt = buildSystemPrompt(profile);
        ledger.addSystem(systemPrompt);
        ledger.addUser(request.prompt());

        log.info("[ReAct] 开始规划 planId={}, userId={}, scene={}",
                planId, request.userId(), profile.isSocialScene() ? "SOCIAL" : "FAMILY");

        // 4. ReAct 主循环
        int step = 0;
        String finalOutput = null;

        while (step < maxSteps) {
            step++;

            // 调用 LLM
            String aiOutput = callLlm(ledger);
            ledger.addAssistant(aiOutput);

            // 解析输出
            JsonNode parsed = parseJsonOutput(aiOutput);
            if (parsed == null) {
                // LLM 输出了非 JSON 的思考内容，继续循环
                ledger.traceThought(aiOutput);
                continue;
            }

            String action = parsed.has("action") ? parsed.get("action").asText() : "";

            // 检查是否终止
            if ("FINISH".equalsIgnoreCase(action)) {
                String summary = parsed.has("summary") ? parsed.get("summary").asText() : aiOutput;
                ledger.traceFinish(summary);
                finalOutput = summary;
                log.info("[ReAct] 规划完成 planId={}, 共{}步", planId, step);
                break;
            }

            // 解析并执行工具调用
            ToolCallResult toolResult = executeToolCall(parsed, ledger);
            ledger.addObservation(toolResult.resultJson());
        }

        if (finalOutput == null) {
            throw new AgentPlanningException("规划迭代超出最大步数上限(" + maxSteps + ")，触发安全熔断");
        }

        // 5. 构建响应
        return buildResponse(planId, request.userId(), finalOutput, ledger);
    }

    /**
     * 调用 LLM
     */
    private String callLlm(ContextLedger ledger) {
        List<Message> messages = ledger.getMessages();
        Prompt prompt = new Prompt(messages);
        ChatResponse response = chatModel.call(prompt);
        return response.getResult().getOutput().getContent();
    }

    /**
     * 从 LLM 输出中提取 JSON（支持 markdown 代码块包裹）
     */
    private JsonNode parseJsonOutput(String aiOutput) {
        // 先去除 markdown 代码块包裹: ```json ... ```
        String cleaned = aiOutput
                .replaceAll("```json\\s*", "")
                .replaceAll("```\\s*", "");

        // 尝试整体解析（纯JSON响应）
        JsonNode node = tryParse(cleaned.trim());
        if (node != null) return node;

        // 正则提取 JSON 对象
        Matcher matcher = JSON_BLOCK.matcher(cleaned);
        while (matcher.find()) {
            node = tryParse(matcher.group());
            if (node != null) return node;
        }

        // fallback: 尝试找任何有效的 JSON 对象（即使没有 action 键）
        Matcher anyJson = Pattern.compile("\\{[^{}]*\\}").matcher(cleaned);
        while (anyJson.find()) {
            node = tryParse(anyJson.group());
            if (node != null) return node;
        }

        return null;
    }

    private JsonNode tryParse(String jsonStr) {
        try {
            JsonNode node = objectMapper.readTree(jsonStr);
            if (node.has("action")) {
                return node;
            }
        } catch (JsonProcessingException ignored) {
        }
        return null;
    }

    /**
     * 执行工具调用
     */
    private ToolCallResult executeToolCall(JsonNode parsed, ContextLedger ledger) {
        String action = parsed.get("action").asText();
        String parameters = parsed.has("parameters")
                ? parsed.get("parameters").toString()
                : "{}";

        ledger.traceAction(action, parameters);

        ToolCallResult result = toolRegistry.execute(action, parameters);

        // 检查是否需要重规划
        if (result.success() && needsReplan(action, result.resultJson())) {
            ledger.incrementReplan();
            log.warn("[ReAct] 触发重规划: action={}, replanCount={}", action, ledger.getReplanCount());

            if (ledger.getReplanCount() > maxReplanAttempts) {
                // 触发降级
                handleDegradation(ledger);
            }
        }

        return result;
    }

    /**
     * 检查是否需要触发重规划
     */
    private boolean needsReplan(String toolName, String resultJson) {
        if (!"checkAvailability".equals(toolName)) return false;

        try {
            JsonNode result = objectMapper.readTree(resultJson);
            String status = result.has("status") ? result.get("status").asText() : "";
            int queueTime = result.has("queueTimeMinutes") ? result.get("queueTimeMinutes").asInt() : 0;

            return "SOLD_OUT".equals(status) || queueTime > queueThresholdMinutes;
        } catch (JsonProcessingException e) {
            return false;
        }
    }

    /**
     * 三级降级策略
     */
    private void handleDegradation(ContextLedger ledger) {
        int replanCount = ledger.getReplanCount();

        if (replanCount <= 2) {
            // 一级：扩大搜索半径
            int newRadius = Math.min(ledger.getCurrentRadiusKm() + 1, maxRadiusKm);
            ledger.expandRadius(newRadius);
            ledger.addSystem(String.format(
                    "[降级策略 一级] 连续重规划无结果，已将搜索半径扩大至 %dkm，请重新搜索。", newRadius));
            log.info("[Degradation] L1: 扩大半径至 {}km", newRadius);

        } else if (replanCount <= 3) {
            // 二级：建议颠倒时间片
            ledger.addSystem(
                    "[降级策略 二级] 当前时间片顺序无法满足约束，建议颠倒活动与餐饮顺序，或调整就餐时间避开高峰。");
            log.info("[Degradation] L2: 建议时序调整");

        } else {
            // 三级：放宽偏好约束
            ledger.markDegraded("已为您降级选择普通餐饮，建议点餐时嘱托少油少盐，选择清淡菜品。");
            ledger.addSystem(
                    "[降级策略 三级] 硬约束与物理现实冲突。请放宽'绝对低脂/轻食'限制，从普通中餐厅中选择替代方案，"
                            + "并在最终方案的总结中加注降级提示。");
            log.info("[Degradation] L3: 偏好降级");
        }
    }

    /**
     * 构建 System Prompt
     */
    private String buildSystemPrompt(UserProfile profile) {
        String basePrompt = loadSystemPrompt();

        StringBuilder scene = new StringBuilder();
        scene.append("\n\n## 当前场景约束\n\n");

        if (profile.isSocialScene()) {
            scene.append("- **场景类型**: 朋友社交聚会\n");
            scene.append("- **人数**: ").append(profile.headcount()).append("人\n");
            scene.append("- **偏好**: 社交属性活动、展览、Citywalk、特色小吃街\n");
            scene.append("- **标签过滤**: social_entertainment, social_dining\n");
        } else {
            scene.append("- **场景类型**: 家庭周末休闲\n");
            scene.append("- **人数**: ").append(profile.headcount()).append("人");
            if (profile.childCount() > 0) {
                scene.append("（含").append(profile.childCount()).append("名儿童）");
            }
            scene.append("\n");
            scene.append("- **饮食约束**: 轻食/低脂/健康\n");
            scene.append("- **标签过滤**: child_friendly, dietary_type=light\n");
            scene.append("- **排除**: 密室逃脱等不适龄场所\n");
        }

        scene.append("- **出发时间**: ").append(profile.startTime()).append("\n");
        scene.append("- **活动时长**: ").append(profile.preferredHours()).append("小时\n");
        scene.append("- **距离限制**: ").append(profile.maxRadiusKm()).append("km 以内\n");

        scene.append("\n## 可用工具\n\n");
        scene.append(toolRegistry.getToolDefinitions());

        scene.append("\n## 时间片模板\n\n");
        scene.append("[14:00-16:00 活动] → [16:00-16:30 交通] → [16:30-18:30 餐饮] → [18:30-20:00 轻度活动/返程]\n");

        return basePrompt + scene.toString();
    }

    private String loadSystemPrompt() {
        try {
            return systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("无法加载 system prompt 文件，使用默认 Prompt");
            return "你是一个本地周末活动规划智能体。请帮用户规划周末活动方案。";
        }
    }

    /**
     * 构建最终响应
     */
    private PlanResponse buildResponse(String planId, String userId, String summary,
                                        ContextLedger ledger) {
        List<PlanStep> timeline = extractTimeline(summary, ledger);
        String orderGroupId = "G" + (700 + planId.hashCode() % 100);

        String notificationText = String.format(
                "搞定了，%s出发。方案已安排好: %s。订单编号: %s",
                "14:00", summary.length() > 80 ? summary.substring(0, 80) + "..." : summary,
                orderGroupId);

        return new PlanResponse(
                planId,
                userId,
                ledger.isDegraded() ? "DEGRADED" : "SUCCESS",
                summary,
                timeline,
                ledger.getTrace(),
                orderGroupId,
                notificationText,
                ledger.getDegradationNote()
        );
    }

    /**
     * 从 LLM 输出中提取时间线
     */
    private List<PlanStep> extractTimeline(String summary, ContextLedger ledger) {
        List<PlanStep> steps = new ArrayList<>();
        List<PoiDto> pois = ledger.getSelectedPois();

        if (pois.isEmpty() && summary != null) {
            // LLM 在文本中描述了时间线，尝试解析
            steps.add(new PlanStep("14:00-16:00", "ACTIVITY", "按方案", "", "游玩", "已确认", ""));
            steps.add(new PlanStep("16:00-16:30", "TRANSIT", "", "", "交通", "", ""));
            steps.add(new PlanStep("16:30-18:30", "DINING", "按方案", "", "餐饮", "已确认", ""));
            steps.add(new PlanStep("18:30-20:00", "EVENING", "", "", "轻度活动/返程", "", ""));
        }

        for (PoiDto poi : pois) {
            String phase = "ACTIVITY".equals(poi.category()) ? "ACTIVITY" : "DINING";
            steps.add(new PlanStep("", phase, poi.name(), poi.poiId(), "",
                    "已确认", String.join(",", poi.tags())));
        }

        return steps;
    }
}
