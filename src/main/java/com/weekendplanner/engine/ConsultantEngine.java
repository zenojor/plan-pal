package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.RestaurantReservationTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;

/**
 * 探索咨询引擎 - 负责处理模糊探索类的咨询推荐问答，
 * 提供超快响应，支持实时商户卡片嵌入和一键拼图行程构建。
 */
@Component
public class ConsultantEngine {

    private static final Logger log = LoggerFactory.getLogger(ConsultantEngine.class);

    private final ChatModel chatModel;
    private final MockPoiDatabase poiDatabase;
    private final RestaurantReservationTool reservationTool;
    private final ReActEngine reactEngine;
    private final FastPlanEngine fastPlanEngine;
    private final ObjectMapper objectMapper;

    public ConsultantEngine(ChatModel chatModel,
                            MockPoiDatabase poiDatabase,
                            RestaurantReservationTool reservationTool,
                            ReActEngine reactEngine,
                            FastPlanEngine fastPlanEngine,
                            ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.poiDatabase = poiDatabase;
        this.reservationTool = reservationTool;
        this.reactEngine = reactEngine;
        this.fastPlanEngine = fastPlanEngine;
        this.objectMapper = objectMapper;
    }

    public void executeConsultStream(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent intent) {
        log.info("[ConsultantEngine] 开启智能两阶段异步探索建议会话 prompt={}", request.prompt());
        String planId = request.planId() != null ? request.planId() : UUID.randomUUID().toString().substring(0, 8);

        // 步骤 1：发射 START 开始信号
        emitter.accept(new SseEvent("START", 0, "🔍 开始理解偏好，开启智能出行探索...", List.of(),
                null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        // 步骤 2：构建 AI 方向性灵感提示词（先不提及具体商户）
        String systemPrompt = """
                你是 PlanPal 智能出行咨询专家，正在为用户量身定制方向性出行灵感建议。

                请针对用户输入的具体出行目的（如用户所述老同学聚会、约会、亲子等偏好），写出一段温馨、有趣、富有条理的游玩灵感与方向性路线规划建议。
                无论用户是什么出行目的，你的推荐人设与偏好口吻都应与该具体目的高度契合。

                ⚠️ 必须遵守的硬性规则：
                1. 请提供具有场景连贯性的方向性建议路线（例如：先去游玩或散步，再去吃个热闹丰盛的烧烤或火锅，最后找一家安静高雅的清吧浅醺小憩）。
                2. ⚠️ 严禁在此阶段自行虚构或写出任何具体的商户名字或商户唯一ID。
                3. 攻略的结尾千万不要写任何“我可以为您构建完整的拼图方案”之类的话。
                """;

        StringBuilder textAccumulated = new StringBuilder();
        try {
            // 步骤 3：第一阶段 LLM 输出一般性游玩建议
            Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux = chatModel.stream(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(request.prompt())
            )));

            // 阻塞当前异步流，逐字/词通过 SSE 推送给前端左侧气泡
            responseFlux.doOnNext(chatResponse -> {
                String chunk = chatResponse.getResult().getOutput().getContent();
                if (chunk != null) {
                    textAccumulated.append(chunk);
                    emitter.accept(new SseEvent("THOUGHT", 3, textAccumulated.toString(), List.of(),
                            null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                }
            }).blockLast();

            // 步骤 4：第二阶段 异步数据库调取与筛选状态呈现
            textAccumulated.append("\n\n> ⚙️ 正在为您寻找符合物理距离与排队状态的商家...");
            emitter.accept(new SseEvent("THOUGHT", 3, textAccumulated.toString(), List.of(),
                    null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

            // 提取输入标签并检索候选 POI
            List<String> tags = detectTags(request.prompt());
            List<PoiDto> activityPois = poiDatabase.searchByCategory("ACTIVITY", tags, 5);
            List<PoiDto> restaurantPois = poiDatabase.searchByCategory("RESTAURANT", tags, 5);

            List<PoiDto> allCandidates = new ArrayList<>();
            allCandidates.addAll(activityPois);
            allCandidates.addAll(restaurantPois);

            // 快速对候选进行排队检查与过滤
            List<PoiDto> availablePois = new ArrayList<>();
            boolean allQueuedOrDegraded = true;

            for (PoiDto poi : allCandidates) {
                try {
                    String params = String.format("{\"poiId\":\"%s\",\"targetTime\":\"14:00\",\"headcount\":2}", poi.poiId());
                    String checkJson = reservationTool.execute(params);
                    CheckResponse check = objectMapper.readValue(checkJson, CheckResponse.class);

                    if (!"QUEUED".equalsIgnoreCase(check.status()) || check.queueTimeMinutes() <= 30) {
                        availablePois.add(poi);
                        allQueuedOrDegraded = false;
                    }
                } catch (Exception e) {
                    availablePois.add(poi); // 异常时兜底加入候选
                }
            }

            // 若全部排满或者为空，触发 ReAct 深度寻找 fallback 兜底搜寻
            if (allCandidates.isEmpty() || allQueuedOrDegraded) {
                log.warn("[ConsultantEngine] 发现精选地点排队全部超时，回退至 ReActEngine 进行备选兜底搜寻...");
                textAccumulated.append("\n> ⚠️ 提示：部分热门商户拥挤极其严重，正在启动 ReAct 深度引擎重新搜寻更舒适的商家...");
                emitter.accept(new SseEvent("THOUGHT", 3, textAccumulated.toString(), List.of(),
                        null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                reactEngine.executePlanStreaming(request, emitter);
                return;
            }

            // 步骤 5：将完成查取状态及真实 POI 语法块追加在文本尾部
            textAccumulated.append("\n> ✨ 思考与调取已完成！为您甄选出以下真实可用的优质商户：\n\n");
            for (int i = 0; i < Math.min(3, availablePois.size()); i++) {
                PoiDto poi = availablePois.get(i);
                String catName = "ACTIVITY".equals(poi.category()) ? "经典体验" : "美食餐饮";
                textAccumulated.append(String.format("- **%s**：🍃 [POI:%s:%s] — 距离约 %.1fkm，排队极佳，推荐时长约 %d分钟。\n", 
                        catName, poi.poiId(), poi.name(), poi.distanceKm(), poi.recommendedDurationMinutes()));
            }

            textAccumulated.append("\n如果你愿意的话，我可以为你构建完整的拼图方案。只需点击下方一键按钮，我将自动帮您拼合好刚才推荐的全部地点与出行路线衔接！");

            // 发送最终的 THOUGHT，使左侧气泡输出完美的富文本攻略与 POI 小卡片
            emitter.accept(new SseEvent("THOUGHT", 3, textAccumulated.toString(), List.of(),
                    null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

            // 步骤 6：基于 Intent 的 sceneType 动态计算精简的顶栏 Summary（防场景硬编码穿帮）
            String sceneDesc = "出行";
            if (intent != null) {
                if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                    sceneDesc = "聚会 / 社交";
                } else if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
                    sceneDesc = "家庭 / 亲子";
                } else if ("SOLO".equalsIgnoreCase(intent.sceneType())) {
                    sceneDesc = "个人出行";
                } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
                    sceneDesc = "温馨约会";
                }
            }
            String shortSummary = String.format("为您精选了 %d 处贴切的%s出行灵感建议！", Math.min(3, availablePois.size()), sceneDesc);

            // 发射 FINISH 完成信号
            emitter.accept(new SseEvent("FINISH", 4, shortSummary, List.of(),
                    "SUCCESS", "", "", null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        } catch (Exception e) {
            log.error("[ConsultantEngine] LLM 探索咨询流式输出失败", e);
            emitter.accept(new SseEvent("ERROR", 5, "很抱歉，在探索咨询时网络出现了一点小风波，请您重试一下。", List.of(),
                    null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        }
    }

    private List<String> detectTags(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        List<String> tags = new ArrayList<>();

        if (lower.contains("约会") || lower.contains("情侣") || lower.contains("女朋友") || lower.contains("男朋友") || lower.contains("亲密")) {
            tags.addAll(List.of("quiet_bar", "bar", "dessert", "movie", "photo", "social_dining", "exhibition"));
        }
        if (lower.contains("孩子") || lower.contains("带娃") || lower.contains("亲子") || lower.contains("儿童") || lower.contains("宝宝")) {
            tags.addAll(List.of("child_friendly", "outdoor", "indoor", "science", "sports", "free"));
        }
        if (lower.contains("蹦迪") || lower.contains("夜店") || lower.contains("聚会") || lower.contains("小组") || lower.contains("蹦野")) {
            tags.addAll(List.of("club", "nightclub", "dance", "late_night", "livehouse", "party", "social_dining"));
        }
        if (lower.contains("吃辣") || lower.contains("辣") || lower.contains("火锅") || lower.contains("川湘") || lower.contains("重口味")) {
            tags.addAll(List.of("spicy", "sichuan", "hunan", "hotpot", "crayfish"));
        }
        if (lower.contains("清淡") || lower.contains("健康") || lower.contains("轻食") || lower.contains("素食")) {
            tags.addAll(List.of("dietary_type=light", "healthy", "vegan", "quiet"));
        }
        if (lower.contains("甜品") || lower.contains("果汁") || lower.contains("咖啡") || lower.contains("下午茶")) {
            tags.addAll(List.of("smoothie", "dessert", "juice", "tea", "coffee"));
        }

        if (tags.isEmpty()) {
            tags.addAll(List.of("social_entertainment", "movie", "bar", "coffee", "dessert", "casual"));
        }
        return tags;
    }
}
