package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一次性意图抽取：优先 LLM JSON，失败后规则兜底。
 */
@Component
public class IntentExtractor {

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${agent.intent.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Value("${agent.intent.timeout-ms:2500}")
    private long llmTimeoutMs = 2500;

    @Autowired
    public IntentExtractor(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this(chatModelProvider.getIfAvailable(), objectMapper);
    }

    public IntentExtractor(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public PlanIntent extract(String prompt) {
        PlanIntent rawFallback = extractByRules(prompt);
        PlanIntent fallback = normalize(rawFallback, rawFallback); // 确保 Fallback 自身已经跑过一次 normalize 覆盖规则！
        if (!llmEnabled || chatModel == null) {
            return fallback;
        }

        try {
            return CompletableFuture.supplyAsync(() -> extractByLlm(prompt, fallback))
                    .get(llmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private PlanIntent extractByLlm(String prompt, PlanIntent fallback) {
        try {
            String schema = """
                    你是行程规划意图抽取器。只输出 JSON，不要解释。
                    字段:
                    headcount:number,
                    participants:string[],
                    startTime:HH:mm,
                    endTime:HH:mm,
                    totalMinutes:number,
                    sceneType:SOLO|FAMILY|SOCIAL,
                    requestedSegments:string[] 值可为 ACTIVITY,DINING,DRINKS,LEISURE,
                    dietaryConstraints:string[],
                    drinkPreference:string,
                    locationScope:string,
                    isConsultingMode:boolean (如果用户是想咨询、寻找灵感、推荐、问去哪好，而不是直接生成具体几点几分的拼图行程方案，请设为 true)
                    """;
            String content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(schema),
                    new UserMessage(prompt)
            ))).getResult().getOutput().getContent();

            JsonNode node = objectMapper.readTree(extractJson(content));
            PlanIntent llmIntent = new PlanIntent(
                    intOr(node, "headcount", fallback.headcount()),
                    listOr(node, "participants", fallback.participants()),
                    textOr(node, "startTime", fallback.startTime()),
                    textOr(node, "endTime", fallback.endTime()),
                    intOr(node, "totalMinutes", fallback.totalMinutes()),
                    textOr(node, "sceneType", fallback.sceneType()),
                    listOr(node, "requestedSegments", fallback.requestedSegments()),
                    listOr(node, "dietaryConstraints", fallback.dietaryConstraints()),
                    textOr(node, "drinkPreference", fallback.drinkPreference()),
                    textOr(node, "locationScope", fallback.locationScope()),
                    prompt,
                    boolOr(node, "isConsultingMode", fallback.isConsultingMode())
            );
            return normalize(llmIntent, fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private PlanIntent extractByRules(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        List<String> participants = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        List<String> segments = new ArrayList<>();

        int headcount = parseHeadcount(lower);
        boolean hasChild = contains(lower, "孩子", "小孩", "宝宝", "儿子", "女儿", "娃", "亲子", "儿童");
        boolean hasWife = contains(lower, "老婆", "妻子", "太太");
        boolean hasFriend = contains(lower, "朋友", "哥们", "闺蜜", "兄弟", "姐妹", "同事");

        if (hasWife) participants.add("老婆");
        if (hasChild) participants.add("孩子");
        if (hasFriend) participants.add("朋友");
        if (headcount == 1) participants.add("一个人");

        if (headcount <= 0) {
            if (hasWife && hasChild) headcount = 3;
            else if (hasWife) headcount = 2;
            else if (hasFriend) headcount = 4;
            else headcount = 1;
        }

        if (contains(lower, "不能吃辣", "不吃辣", "忌辣", "少辣", "不要辣")) {
            constraints.add("NO_SPICY");
        }
        if (contains(lower, "减肥", "轻食", "低脂", "低卡", "健康", "沙拉", "素食")) {
            constraints.add("LIGHT_HEALTHY");
        }

        String startTime = parseStartTime(lower);
        String endTime = parseEndTime(lower, startTime);
        int totalMinutes = minutesBetween(startTime, endTime);

        boolean wantsDining = contains(lower, "吃", "饭", "餐", "晚饭", "夜宵", "小吃", "烧烤", "火锅", "川菜", "湘菜", "冰沙", "奶茶", "甜品", "咖啡");
        boolean wantsDrinks = contains(lower, "bar", "酒吧", "清吧", "喝一杯", "喝点", "好喝", "鸡尾酒", "精酿", "club", "蹦迪", "夜店", "livehouse");
        boolean wantsActivity = contains(lower, "玩", "活动", "展", "电影", "散步", "citywalk", "逛");

        if (wantsDining) segments.add("DINING");
        if (wantsDrinks) segments.add("DRINKS");
        if (wantsActivity) segments.add("LEISURE");
        if (segments.isEmpty()) {
            segments.add("ACTIVITY");
            segments.add("DINING");
        }
        if (wantsDrinks && !segments.contains("LEISURE") && totalMinutes >= 180) {
            segments.add("LEISURE");
        }

        String sceneType = headcount == 1 ? "SOLO" : hasFriend ? "SOCIAL" : "FAMILY";
        String drinkPreference = wantsDrinks ? "bar/drinks" : "";
        String locationScope = contains(lower, "远一点", "远些", "全城", "10km", "10公里") ? "WIDE" : "NEARBY";

        boolean isConsultingMode = detectConsultingMode(prompt);
        return new PlanIntent(headcount, List.copyOf(participants), startTime, endTime,
                totalMinutes, sceneType, List.copyOf(segments), List.copyOf(constraints),
                drinkPreference, locationScope, prompt, isConsultingMode);
    }

    private PlanIntent normalize(PlanIntent intent, PlanIntent fallback) {
        String start = isTime(intent.startTime()) ? intent.startTime() : fallback.startTime();
        String end = isTime(intent.endTime()) ? intent.endTime() : fallback.endTime();
        int total = minutesBetween(start, end);
        int headcount = intent.headcount() > 0 ? intent.headcount() : fallback.headcount();
        List<String> segments = intent.requestedSegments() == null || intent.requestedSegments().isEmpty()
                ? fallback.requestedSegments() : intent.requestedSegments();

        boolean isConsultingMode = intent.isConsultingMode();

        // 关键自检修正：若用户显式提供了时间/时长信息且提供了人数/人员信息，代表参数高度完整，应该直接进入拼图规划（PLAN）模式！
        String promptLower = intent.originalPrompt() != null ? intent.originalPrompt().toLowerCase(java.util.Locale.ROOT) : "";
        boolean hasExplicitTimeInfo = promptLower.contains("点") || promptLower.contains("分") || promptLower.contains("时")
                || promptLower.contains("小时") || promptLower.contains("分钟") || promptLower.contains("钟头")
                || promptLower.contains("am") || promptLower.contains("pm") || promptLower.contains("：") || promptLower.contains(":")
                || promptLower.contains("下午") || promptLower.contains("晚上") || promptLower.contains("中午")
                || promptLower.contains("上午") || promptLower.contains("早上") || promptLower.contains("夜里") || promptLower.contains("凌晨");
        boolean hasExplicitHeadcountInfo = headcount > 0 
                || (intent.participants() != null && !intent.participants().isEmpty())
                || promptLower.contains("人") || promptLower.contains("位") || promptLower.contains("独自") 
                || promptLower.contains("自己") || promptLower.contains("情侣") || promptLower.contains("老婆") 
                || promptLower.contains("老公") || promptLower.contains("孩子") || promptLower.contains("娃") 
                || promptLower.contains("朋友") || promptLower.contains("聚会") || promptLower.contains("聚聚");

        if (hasExplicitTimeInfo && hasExplicitHeadcountInfo) {
            isConsultingMode = false;
        }

        return new PlanIntent(headcount,
                safeList(intent.participants(), fallback.participants()),
                start,
                end,
                total,
                text(intent.sceneType(), fallback.sceneType()),
                segments,
                safeList(intent.dietaryConstraints(), fallback.dietaryConstraints()),
                text(intent.drinkPreference(), fallback.drinkPreference()),
                text(intent.locationScope(), fallback.locationScope()),
                intent.originalPrompt(),
                isConsultingMode);
    }

    // ==================== 对话微调专用合并 ====================

    /**
     * 对话微调专用合并：以原始 intent 为基线，仅从用户的纯调整文本中提取增量变更。
     * 避免从混合了 timeline 描述的构造 prompt 中重新提取导致上下文污染。
     */
    public PlanIntent mergeForAdjustment(PlanIntent original, String adjustmentPrompt) {
        String lower = adjustmentPrompt.toLowerCase(Locale.ROOT);

        // 1. 人数：仅当用户明确提及人数/人员时才覆盖
        boolean mentionsHeadcount = contains(lower, "人", "位", "独自", "朋友", "老婆",
                "孩子", "情侣", "聚会", "个人");
        int headcount;
        List<String> participants;
        if (mentionsHeadcount) {
            PlanIntent adj = extractByRules(adjustmentPrompt);
            headcount = adj.headcount() > 0 ? adj.headcount() : original.headcount();
            participants = adj.participants().isEmpty() ? original.participants() : adj.participants();
        } else {
            headcount = original.headcount();
            participants = original.participants();
        }

        // 2. 时间：处理"顺延/延长"语义（仅修改 endTime，保持 startTime 不变）
        boolean isExtend = contains(lower, "顺延", "延长", "延到", "推迟", "推后");
        boolean mentionsTime = contains(lower, "点", "分", "时", "am", "pm", ":", "：",
                "下午", "晚上", "上午", "早上", "中午");

        String startTime, endTime;
        if (isExtend && mentionsTime) {
            startTime = original.startTime();
            endTime = parseTargetHour(lower, original);
        } else if (mentionsTime) {
            startTime = parseStartTime(lower);
            endTime = parseEndTime(lower, startTime);
        } else {
            startTime = original.startTime();
            endTime = original.endTime();
        }
        int totalMinutes = minutesBetween(startTime, endTime);

        // 3. 场景分段：增量追加（"多安排一个活动"等）
        List<String> segments = new ArrayList<>(original.requestedSegments());
        if (contains(lower, "多安排", "多加", "增加", "加一个", "再来", "再加")) {
            if (contains(lower, "活动", "玩", "展", "电影", "散步")) {
                segments.add("LEISURE");
            }
            if (contains(lower, "吃", "饭", "餐", "美食")) {
                segments.add("DINING");
            }
            if (contains(lower, "酒", "bar", "喝", "蹦迪")) {
                segments.add("DRINKS");
            }
        }

        // 4. 约束：保留原始 + 增量追加
        List<String> constraints = new ArrayList<>(original.dietaryConstraints());
        if (contains(lower, "不能吃辣", "不吃辣", "忌辣", "少辣", "不要辣") && !constraints.contains("NO_SPICY")) {
            constraints.add("NO_SPICY");
        }
        if (contains(lower, "减肥", "轻食", "低脂", "低卡", "健康") && !constraints.contains("LIGHT_HEALTHY")) {
            constraints.add("LIGHT_HEALTHY");
        }

        return new PlanIntent(headcount, participants, startTime, endTime, totalMinutes,
                original.sceneType(), List.copyOf(segments), List.copyOf(constraints),
                original.drinkPreference(), original.locationScope(),
                adjustmentPrompt, false);
    }

    /**
     * 解析"顺延到X点"中的目标小时，支持中文数字。
     * 在下午/晚间语境中自动将 < 12 的小时解释为 PM。
     */
    private String parseTargetHour(String text, PlanIntent original) {
        // 有序：最长的先匹配，避免"十一"被"十"截断
        String[][] cnHours = {
                {"十二", "12"}, {"十一", "11"}, {"十", "10"},
                {"九", "9"}, {"八", "8"}, {"七", "7"}, {"六", "6"},
                {"五", "5"}, {"四", "4"}, {"三", "3"}, {"二", "2"}, {"一", "1"}
        };

        for (String[] pair : cnHours) {
            if (text.contains(pair[0] + "点")) {
                int hour = Integer.parseInt(pair[1]);
                // 下午/晚间语境：原始 startTime >= 12:00 时，将 < 12 的小时解释为 PM
                if (toMinutes(original.startTime()) >= 12 * 60 && hour < 12) {
                    hour += 12;
                }
                return String.format(Locale.ROOT, "%02d:00", hour);
            }
        }

        // 数字模式: "到11点", "延到23点"
        Matcher m = Pattern.compile("(\\d{1,2})\\s*[:：点]").matcher(text);
        if (m.find()) {
            int hour = Integer.parseInt(m.group(1));
            if (toMinutes(original.startTime()) >= 12 * 60 && hour < 12) {
                hour += 12;
            }
            return String.format(Locale.ROOT, "%02d:00", hour);
        }

        // Fallback: 原始 endTime + 120 分钟
        return addMinutes(original.endTime(), 120);
    }

    private int parseHeadcount(String text) {
        if (contains(text, "一个人", "1个人", "1 人", "独自", "自己一个")) return 1;
        Matcher digit = Pattern.compile("(\\d+)\\s*(个)?人").matcher(text);
        if (digit.find()) return Integer.parseInt(digit.group(1));
        Map<String, Integer> cn = Map.of("一", 1, "二", 2, "两", 2, "三", 3, "四", 4, "五", 5, "六", 6);
        Matcher chinese = Pattern.compile("([一二两三四五六])个?人").matcher(text);
        if (chinese.find()) return cn.getOrDefault(chinese.group(1), 0);
        return 0;
    }

    private String parseStartTime(String text) {
        if (contains(text, "晚上八点", "晚八点", "8点后", "八点后", "20点")) return "20:00";
        if (contains(text, "晚上九点", "晚九点", "9点后", "九点后", "21点")) return "21:00";
        if (contains(text, "上午", "早上", "10点", "十点")) return "10:00";
        Matcher hour = Pattern.compile("(\\d{1,2})[:：点](\\d{0,2})").matcher(text);
        if (hour.find()) {
            int h = Integer.parseInt(hour.group(1));
            int m = hour.group(2).isBlank() ? 0 : Integer.parseInt(hour.group(2));
            if (contains(text, "晚上", "晚") && h < 12) h += 12;
            return String.format(Locale.ROOT, "%02d:%02d", h, m);
        }
        return "14:00";
    }

    private String parseEndTime(String text, String startTime) {
        if (contains(text, "到十二点", "玩到十二点", "一直到十二点")) {
            return toMinutes(startTime) >= 18 * 60 ? "24:00" : "12:00";
        }
        if (contains(text, "到凌晨一点", "到1点", "到一点")) return "25:00";
        Matcher until = Pattern.compile("到\\s*(\\d{1,2})[:：点](\\d{0,2})").matcher(text);
        if (until.find()) {
            int h = Integer.parseInt(until.group(1));
            int m = until.group(2).isBlank() ? 0 : Integer.parseInt(until.group(2));
            if (toMinutes(startTime) >= 18 * 60 && h <= 12) h += 12;
            return String.format(Locale.ROOT, "%02d:%02d", h, m);
        }
        return addMinutes(startTime, 240);
    }

    private int minutesBetween(String start, String end) {
        return Math.max(60, toMinutes(end) - toMinutes(start));
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String addMinutes(String time, int minutes) {
        int total = toMinutes(time) + minutes;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private boolean isTime(String time) {
        return time != null && time.matches("\\d{1,2}:\\d{2}");
    }

    private boolean contains(String text, String... keywords) {
        for (String kw : keywords) {
            if (text.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private int intOr(JsonNode node, String field, int fallback) {
        return node.has(field) ? node.path(field).asInt(fallback) : fallback;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        return node.has(field) ? node.path(field).asText(fallback) : fallback;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private List<String> listOr(JsonNode node, String field, List<String> fallback) {
        if (!node.has(field) || !node.path(field).isArray()) return fallback;
        List<String> values = new ArrayList<>();
        node.path(field).forEach(item -> values.add(item.asText()));
        return values;
    }

    private List<String> safeList(List<String> value, List<String> fallback) {
        return value == null || value.isEmpty() ? fallback : value;
    }

    private boolean boolOr(JsonNode node, String field, boolean fallback) {
        return node.has(field) ? node.path(field).asBoolean(fallback) : fallback;
    }

    private boolean detectConsultingMode(String prompt) {
        String lower = prompt.toLowerCase(Locale.ROOT);
        // 包含咨询、推荐相关的常见关键词
        boolean hasConsultKeywords = contains(lower, "去哪", "推荐", "什么好", "有什么", "攻略", "好玩", "比较好", "约会去哪", "好去处", "去哪里", "好地方", "带女朋友", "带娃去哪");
        // 是否包含明确的几点几分等时间标识，例如：14点，14:00，2点等
        boolean hasSpecificTime = contains(lower, "点", "分", "时", "am", "pm", "clock", "：", ":");
        // 若含有咨询倾向且无明确日程安排，则进入咨询探索模式
        return hasConsultKeywords && !hasSpecificTime;
    }
}
