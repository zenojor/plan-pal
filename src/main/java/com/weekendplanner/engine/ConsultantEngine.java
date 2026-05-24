package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
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
    private final PoiProvider poiDatabase;
    private final RestaurantReservationTool reservationTool;
    private final ReActEngine reactEngine;
    private final FastPlanEngine fastPlanEngine;
    private final ObjectMapper objectMapper;

    public ConsultantEngine(ChatModel chatModel,
                            PoiProvider poiDatabase,
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
        log.info("[ConsultantEngine] 开启智能探索建议会话 prompt={}", request.prompt());
        String planId = request.planId() != null ? request.planId() : UUID.randomUUID().toString().substring(0, 8);

        // 步骤 1：前置商户并行检索与排队时间校验
        List<String> tags = detectTags(request.prompt(), intent);
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
            emitter.accept(new SseEvent("START", 0, "🔍 开始理解偏好，开启深度 ReAct 搜寻...", List.of(),
                    null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
            reactEngine.executePlanStreaming(request, emitter, intent);
            return;
        }

        // 步骤 2：发射 START 开始信号
        emitter.accept(new SseEvent("START", 0, "🔍 开始理解偏好，开启智能出行探索...", List.of(),
                null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        // 步骤 3：构建真实 POI 列表描述
        StringBuilder poiListStr = new StringBuilder();
        for (PoiDto poi : availablePois) {
            poiListStr.append(String.format("- [POI:%s:%s]：类型：%s，距离约 %.1fkm，推荐时长约 %d分钟，标签：%s\n",
                    poi.poiId(), poi.name(), 
                    "ACTIVITY".equals(poi.category()) ? "ACTIVITY(经典体验)" : "RESTAURANT(美食餐饮)",
                    poi.distanceKm(), poi.recommendedDurationMinutes(),
                    String.join(", ", poi.tags())));
        }

        // 步骤 4：构建极富美感、直击本质、强结构化的 AI 系统提示词
        String systemPrompt = """
                你是 PlanPal 智能出行咨询专家，正在为用户量身定制方向性出行灵感建议。
                你的回答风格应当极其专业、具有深刻洞察力、精炼且充满启发性，绝不废话，就像一位品味极高的知性朋友在出谋划策。

                ⚠️ 必须严格遵守的【硬性风格与排版规则】（参考以下黄金框架进行排版）：

                1. 💡 **直击核心本质（第一要素）**：
                   - 绝不要用任何虚套、谄媚或角色扮演式的开场白（例如“第一次约会啊，真让人心跳加速！”或“没问题，我来帮你安排”等官方废话，一律严禁）。
                   - 开篇直接指出对于该出行场景（如约会、聚会、亲子、独处等）而言，最核心的成功要素/痛点是什么。使用简洁的无序列表展示。
                   - 例如（约会场景）：
                     "第一次约会最重要的其实不是“高级”，而是：
                     - 能自然聊天
                     - 不会太尴尬
                     - 有点共同体验
                     - 随时能撤退或续场"

                2. 👤 **用户心智/取向剖析**：
                   - 用一两句极其到位的话，简短分析并定性用户潜在的审美、情绪价值或社交取向。
                   - 例如："你这种偏音乐/审美/氛围感取向的人，其实挺适合“有 vibe 但不压迫”的地方。"

                3. 🗺️ **结构化出行方案选择（提供 2 个最具实操性的活动组合/类型）**：
                   - 使用清晰的大标题和加粗标出选项（例如：**咖啡店 + 散步（最稳）**，**小展览 / 市集 / 书店**）。
                   - 每一个方案下包含：
                     - **适用情况/一句话定位**（例如：适合第一次真正见面）。
                     - **核心优点**：用无序列表说明为什么这么推荐。
                     - **筛选标准/最好选**：用无序列表明确指出这类地点的具体环境细节、审美特征或周边条件（例如：“最好选：有点工业风/复古/韩系/underground vibe的店”、“晚上灯光别太亮”、“周围最好能接着散步”）。
                     - **真实可用商户推荐**：在上述方案描述或筛选标准中，你必须且只能从下面【提供给您的真实商户列表】中挑选 1 个最契合方案的商户，巧妙自然地以 `[POI:poiId:poiName]` 的格式穿插融入进去（例如：“适合在 [POI:P028:小橘子果汁咖啡] 喝杯下午茶，接着...”），并简短说一句话为什么它极其契合。**注意：只能推荐列表里存在的商户，严禁虚构或改动 ID / 名字！**

                4. 🚫 **硬性禁忌规则**：
                   - 严禁自己虚构任何不存在的商户名字或 ID。
                   - 禁止使用任何“AI腔”或官方客套话。
                   - 文本的最后，必须在新起一行输出这一段完全一致的文字以触发前端卡片：
                     "如果你愿意的话，我可以为你构建完整的拼图方案。只需点击下方一键按钮，我将自动帮您拼合好刚才推荐的全部地点与出行路线衔接！"

                以下是数据库中当前真实存在且排队状态极佳（完全可用）的精选商户列表，请完全根据该列表进行商户选择和标签嵌入：
                """ + poiListStr.toString();

        StringBuilder textAccumulated = new StringBuilder();
        try {
            // 步骤 5：单阶段 LLM 开启 stream 流式输出
            Flux<org.springframework.ai.chat.model.ChatResponse> responseFlux = chatModel.stream(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(request.prompt())
            )));

            // 阻塞当前流，逐字推送
            responseFlux.doOnNext(chatResponse -> {
                String chunk = chatResponse.getResult().getOutput().getContent();
                if (chunk != null) {
                    textAccumulated.append(chunk);
                    emitter.accept(new SseEvent("THOUGHT", 3, textAccumulated.toString(), List.of(),
                            null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                }
            }).blockLast();

            // 步骤 6：基于 Intent 的 sceneType 动态计算精简的顶栏 Summary
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

            // 统计 LLM 实际推荐了多少个 POI 并发送 Summary
            int recommendCount = 0;
            for (PoiDto poi : availablePois) {
                if (textAccumulated.toString().contains(poi.poiId())) {
                    recommendCount++;
                }
            }
            if (recommendCount == 0) {
                recommendCount = Math.min(2, availablePois.size());
            }
            String shortSummary = String.format("为您精选了 %d 处贴切的%s出行灵感建议！", recommendCount, sceneDesc);

            // 发射 FINISH 完成信号
            emitter.accept(new SseEvent("FINISH", 4, shortSummary, List.of(),
                    "SUCCESS", "", "", null, planId, intent, List.of(), "PENDING_CONFIRMATION"));

        } catch (Exception e) {
            log.error("[ConsultantEngine] LLM 探索咨询流式输出失败", e);
            emitter.accept(new SseEvent("ERROR", 5, "很抱歉，在探索咨询时网络出现了一点小风波，请您重试一下。", List.of(),
                    null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        }
    }

    private List<String> detectTags(String prompt, PlanIntent intent) {
        List<String> tags = new ArrayList<>();
        // 基于 LLM 已提取的 sceneType 来选择标签，而非重新做关键词匹配
        if (intent != null && intent.sceneType() != null) {
            switch (intent.sceneType().toUpperCase()) {
                case "DATE" -> tags.addAll(List.of("quiet_bar", "bar", "dessert", "movie", "photo", "social_dining", "exhibition"));
                case "FAMILY" -> tags.addAll(List.of("child_friendly", "outdoor", "indoor", "science", "sports", "free"));
                case "SOCIAL" -> tags.addAll(List.of("social_entertainment", "social_dining", "party", "club", "late_night", "livehouse"));
                case "SOLO" -> tags.addAll(List.of("solo_friendly", "quiet_bar", "coffee", "tea", "casual"));
            }
            if (intent.hasChildren()) {
                tags.addAll(List.of("child_friendly", "indoor", "science"));
            }
        }
        // 仍保留 prompt 关键词做增强（非替代）
        addPromptBasedTags(tags, prompt);
        return tags;
    }

    private void addPromptBasedTags(List<String> tags, String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);

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
    }
}
