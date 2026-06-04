package com.weekendplanner.engine.intent;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.SlotValue;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One-shot trip intent extraction: prefer LLM JSON, then deterministic rules. */
@Component
public class IntentExtractor {

    private static final Set<String> PACE_VALUES = Set.of("RELAXED", "NORMAL", "COMPACT");
    private static final Set<String> BUDGET_VALUES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> TRANSPORT_VALUES = Set.of("WALK", "DRIVE", "PUBLIC_TRANSIT");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;
    private final IntentValidator intentValidator;
    private final TurnUnderstandingService understandingService;
    private final ExecutorService executorService = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        thread.setName("llm-intent-extractor-pool");
        return thread;
    });

    private static final Logger log = LoggerFactory.getLogger(IntentExtractor.class);

    @Value("${agent.intent.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Value("${agent.intent.timeout-ms:30000}")
    private long llmTimeoutMs = 30000;

    @Autowired
    public IntentExtractor(ObjectProvider<ChatModel> chatModelProvider,
                           ObjectMapper objectMapper,
                           IntentValidator intentValidator,
                           TurnUnderstandingService understandingService) {
        this(chatModelProvider.getIfAvailable(), objectMapper, intentValidator, understandingService);
    }

    public IntentExtractor(ChatModel chatModel, ObjectMapper objectMapper, IntentValidator intentValidator) {
        this(chatModel, objectMapper, intentValidator, TurnUnderstandingService.fallbackOnly());
    }

    public IntentExtractor(ChatModel chatModel,
                           ObjectMapper objectMapper,
                           IntentValidator intentValidator,
                           TurnUnderstandingService understandingService) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.intentValidator = intentValidator == null ? new IntentValidator() : intentValidator;
        this.understandingService = understandingService == null ? TurnUnderstandingService.fallbackOnly() : understandingService;
    }

    public IntentExtractor(ChatModel chatModel, ObjectMapper objectMapper) {
        this(chatModel, objectMapper, new IntentValidator());
    }

    public PlanIntent extract(String prompt) {
        PlanIntent ruleResult = extractByRules(prompt == null ? "" : prompt);
        TurnUnderstanding understanding = understandingService.understandInitial(prompt == null ? "" : prompt);
        if (understanding != null && (understanding.hasSlots() || understanding.turnIntent() != TurnIntent.UNKNOWN)) {
            return intentValidator.validate(applyUnderstanding(ruleResult, understanding, prompt), prompt);
        }
        // Phase 1: LLM 优先提取
        if (llmEnabled && chatModel != null) {
            try {
                PlanIntent llmResult = CompletableFuture
                        .supplyAsync(() -> extractByLlmPrimary(prompt), executorService)
                        .get(llmTimeoutMs, TimeUnit.MILLISECONDS);
                if (llmResult != null) {
                    return intentValidator.validate(mergeRuleFirst(ruleResult, llmResult, prompt), prompt);
                }
            } catch (Exception e) {
                log.warn("[IntentExtractor] LLM primary extraction failed or timed out: {}", e.toString());
            }
        }
        // Phase 2: 降级到规则引擎
        PlanIntent ruleFallback = extractByRules(prompt == null ? "" : prompt);
        return intentValidator.validate(ruleFallback, prompt);
    }

    private PlanIntent applyUnderstanding(PlanIntent fallback, TurnUnderstanding understanding, String prompt) {
        int headcount = slotInt(understanding, SlotName.HEADCOUNT, fallback.headcount());
        String startTime = slotText(understanding, SlotName.START_TIME, fallback.startTime());
        String endTime = slotText(understanding, SlotName.END_TIME, fallback.endTime());
        Integer maxDuration = understanding.slot(SlotName.DURATION_RANGE)
                .map(SlotValue::maxMinutes)
                .orElse(null);
        int totalMinutes = maxDuration != null && maxDuration > 0
                ? maxDuration
                : minutesBetween(startTime, endTime);
        if (understanding.slot(SlotName.MAX_END_TIME).isPresent()) {
            endTime = slotText(understanding, SlotName.MAX_END_TIME, endTime);
            totalMinutes = minutesBetween(startTime, endTime);
        }
        String locationScope = slotText(understanding, SlotName.LOCATION_SCOPE, fallback.locationScope());
        String pace = slotText(understanding, SlotName.PACE, fallback.pace());
        String budget = slotText(understanding, SlotName.BUDGET_LEVEL, fallback.budgetLevel());
        String transport = slotText(understanding, SlotName.TRANSPORT_MODE, fallback.preferredTransportMode());
        boolean consulting = fallback.isConsultingMode();
        if (understanding.turnIntent() == TurnIntent.MODIFY_PLAN || understanding.turnIntent() == TurnIntent.FILL_PENDING_SLOTS) {
            consulting = false;
        }
        return new PlanIntent(
                headcount,
                fallback.participants(),
                startTime,
                endTime,
                totalMinutes,
                fallback.sceneType(),
                fallback.requestedSegments(),
                fallback.dietaryConstraints(),
                fallback.drinkPreference(),
                locationScope,
                prompt,
                pace,
                budget,
                fallback.hasChildren(),
                fallback.childAge(),
                transport,
                fallback.avoid(),
                fallback.mustHave(),
                fallback.weatherSensitive(),
                consulting);
    }

    private int slotInt(TurnUnderstanding understanding, SlotName name, int fallback) {
        return understanding.slot(name)
                .map(SlotValue::value)
                .map(value -> {
                    if (value instanceof Number number) return number.intValue();
                    try {
                        return Integer.parseInt(String.valueOf(value).trim());
                    } catch (Exception ignored) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private String slotText(TurnUnderstanding understanding, SlotName name, String fallback) {
        return understanding.slot(name)
                .map(SlotValue::value)
                .map(String::valueOf)
                .filter(value -> !value.isBlank())
                .orElse(fallback);
    }

    private PlanIntent mergeRuleFirst(PlanIntent rule, PlanIntent llm, String prompt) {
        String lower = safeLower(prompt);
        boolean explicitTime = contains(lower, ":", "点", "點", "上午", "下午", "晚上", "中午", "早上", "am", "pm");
        boolean explicitHeadcount = Pattern.compile("\\d+\\s*(人|位|个|個)").matcher(lower).find()
                || contains(lower, "情侣", "夫妻", "老婆", "老公", "孩子", "朋友", "同学", "同事", "聚会");
        boolean explicitBudget = contains(lower, "预算", "便宜", "省钱", "免费", "贵", "高级", "cheap", "premium");
        boolean explicitTransport = contains(lower, "步行", "走路", "开车", "自驾", "公交", "地铁", "walk", "drive", "metro");
        boolean explicitAvoid = contains(lower, "避开", "不要", "不想", "别去", "avoid");

        return new PlanIntent(
                explicitHeadcount ? rule.headcount() : llm.headcount(),
                rule.participants().isEmpty() ? llm.participants() : rule.participants(),
                explicitTime ? rule.startTime() : llm.startTime(),
                explicitTime ? rule.endTime() : llm.endTime(),
                explicitTime ? rule.totalMinutes() : llm.totalMinutes(),
                llm.sceneType(),
                rule.requestedSegments().isEmpty() ? llm.requestedSegments() : rule.requestedSegments(),
                rule.dietaryConstraints().isEmpty() ? llm.dietaryConstraints() : rule.dietaryConstraints(),
                llm.drinkPreference(),
                llm.locationScope(),
                prompt,
                llm.pace(),
                explicitBudget ? rule.budgetLevel() : llm.budgetLevel(),
                rule.hasChildren() || llm.hasChildren(),
                rule.childAge() == null ? llm.childAge() : rule.childAge(),
                explicitTransport ? rule.preferredTransportMode() : llm.preferredTransportMode(),
                explicitAvoid ? rule.avoid() : merge(rule.avoid(), llm.avoid()),
                merge(rule.mustHave(), llm.mustHave()),
                rule.weatherSensitive() || llm.weatherSensitive(),
                llm.isConsultingMode());
    }

    private List<String> merge(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) merged.addAll(first);
        if (second != null) merged.addAll(second);
        return List.copyOf(merged);
    }

    private PlanIntent extractByLlmPrimary(String prompt) {
        PlanIntent parsingFallback = createParsingFallback(prompt);
        try {
            String schema = """
                    You are a trip-planning intent extractor. Output JSON only.
                    Fields:
                    headcount:number (e.g. 3 for family of three/一家三口, 2 for couples/约会, >=3 for 团建/聚会 if unspecified),
                    participants:string[],
                    startTime:HH:mm,
                    endTime:HH:mm,
                    totalMinutes:number,
                    sceneType: Determine from context:
                      - SOLO: only when user explicitly says alone/一个人/独自
                      - DATE: romantic context (约会/date/couple)
                      - SOCIAL: friends/colleagues/战友/同学/聚会/团建/闺蜜
                      - FAMILY: mentions wife/husband/kids/家人/亲子/带娃
                      Note: '老战友' means old army buddies = SOCIAL, not SOLO.
                      Note: '约会' with friends/buddies = SOCIAL, not DATE.
                    requestedSegments:string[] values: ACTIVITY,DINING,DRINKS,LEISURE,
                    dietaryConstraints:string[],
                    drinkPreference:string,
                    locationScope:NEARBY|WIDE,
                    pace:RELAXED|NORMAL|COMPACT,
                    budgetLevel:LOW|MEDIUM|HIGH,
                    hasChildren:boolean,
                    childAge:number|null,
                    preferredTransportMode:WALK|DRIVE|PUBLIC_TRANSIT,
                    avoid:string[],
                    mustHave:string[],
                    weatherSensitive:boolean,
                    isConsultingMode:boolean (true if the user is asking a vague question, seeking suggestions, asking for recommendations, or exploring ideas, e.g., "第一次约会去什么地方好", "推荐几个好玩的地方", "有什么好吃的"; and false if the user is explicitly requesting a concrete plan with structured times or a complete itinerary, e.g., "下午两点到四点去喝咖啡", "给我做一个聚会行程")
                    """;
            String content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(schema),
                    new UserMessage(prompt == null ? "" : prompt)
            ))).getResult().getOutput().getText();

            JsonNode node = objectMapper.readTree(extractJson(content));
            return new PlanIntent(
                    intOr(node, "headcount", parsingFallback.headcount()),
                    listOr(node, "participants", parsingFallback.participants()),
                    textOr(node, "startTime", parsingFallback.startTime()),
                    textOr(node, "endTime", parsingFallback.endTime()),
                    intOr(node, "totalMinutes", parsingFallback.totalMinutes()),
                    textOr(node, "sceneType", parsingFallback.sceneType()),
                    listOr(node, "requestedSegments", parsingFallback.requestedSegments()),
                    listOr(node, "dietaryConstraints", parsingFallback.dietaryConstraints()),
                    textOr(node, "drinkPreference", parsingFallback.drinkPreference()),
                    textOr(node, "locationScope", parsingFallback.locationScope()),
                    prompt,
                    textOr(node, "pace", parsingFallback.pace()),
                    textOr(node, "budgetLevel", parsingFallback.budgetLevel()),
                    boolOr(node, "hasChildren", parsingFallback.hasChildren()),
                    nullableIntOr(node, "childAge", parsingFallback.childAge()),
                    textOr(node, "preferredTransportMode", parsingFallback.preferredTransportMode()),
                    listOr(node, "avoid", parsingFallback.avoid()),
                    listOr(node, "mustHave", parsingFallback.mustHave()),
                    boolOr(node, "weatherSensitive", parsingFallback.weatherSensitive()),
                    boolOr(node, "isConsultingMode", parsingFallback.isConsultingMode())
            );
        } catch (Exception e) {
            log.warn("[IntentExtractor] LLM primary parsing error: {}", e.toString());
            return parsingFallback;
        }
    }

    private PlanIntent createParsingFallback(String prompt) {
        return new PlanIntent(
                1,
                List.of(),
                "14:00",
                "18:00",
                240,
                "SOLO",
                List.of("ACTIVITY", "DINING"),
                List.of(),
                "",
                "NEARBY",
                prompt,
                "NORMAL",
                "MEDIUM",
                false,
                null,
                "PUBLIC_TRANSIT",
                List.of(),
                List.of(),
                false,
                false
        );
    }

    private PlanIntent extractByRules(String prompt) {
        String lower = safeLower(prompt);
        List<String> participants = new ArrayList<>();
        List<String> constraints = new ArrayList<>();
        List<String> segments = new ArrayList<>();

        int headcount = parseHeadcount(lower);
        boolean hasChild = contains(lower, "孩子", "小孩", "宝宝", "娃", "亲子", "儿童", "带娃", "瀛╁瓙", "浜插瓙", "鍎跨");
        Integer childAge = parseChildAge(lower);
        boolean hasPartner = contains(lower, "老婆", "妻子", "老公", "情侣", "女朋友", "男朋友", "鑰佸﹩", "濡诲瓙", "鎯呬荆");
        boolean hasFriend = contains(lower, "朋友", "同学", "同事", "聚会", "闺蜜", "鏈嬪弸", "鑱氫細");

        if (hasPartner) participants.add("伴侣");
        if (hasChild) participants.add("孩子");
        if (hasFriend) participants.add("朋友");
        if (headcount == 1) participants.add("一个人");

        if (headcount <= 0) {
            if (hasPartner && hasChild && hasFriend) headcount = 4;
            else if (hasChild && hasFriend) headcount = 3;
            else if (hasPartner && hasChild) headcount = 3;
            else if (hasPartner) headcount = 2;
            else if (hasChild) headcount = 2;
            else if (hasFriend) headcount = 4;
            else headcount = 1;
        }

        if (contains(lower, "不能吃辣", "不吃辣", "忌辣", "少辣", "不要辣", "涓嶈兘鍚冭荆", "涓嶅悆杈", "蹇岃荆")) {
            constraints.add("NO_SPICY");
        }
        if (contains(lower, "减肥", "轻食", "低脂", "低卡", "健康", "沙拉", "素食", "鍑忚偉", "杞婚", "鍋ュ悍")) {
            constraints.add("LIGHT_HEALTHY");
        }

        String startTime = parseStartTime(lower);
        String endTime = parseEndTime(lower, startTime);
        int totalMinutes = minutesBetween(startTime, endTime);

        boolean wantsDining = contains(lower, "吃", "饭", "餐", "晚饭", "夜宵", "小吃", "烧烤", "火锅", "冰沙", "奶茶", "甜品", "咖啡", "鍚", "楗", "鐑х儰", "鐏攨", "鍐版矙");
        boolean wantsDrinks = contains(lower, "bar", "酒吧", "清吧", "喝", "鸡尾酒", "精酿", "club", "蹦迪", "夜店", "livehouse", "娓呭惂", "楦″熬閰", "绮鹃吙");
        boolean wantsActivity = contains(lower, "玩", "活动", "展", "电影", "散步", "citywalk", "逛", "鐜", "娲诲姩", "鐢靛奖", "鏁ｆ");

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
        String locationScope = contains(lower, "远一点", "远些", "全城", "10km", "10公里", "杩滀竴鐐", "鍏ㄥ煄") ? "WIDE" : "NEARBY";

        return new PlanIntent(
                headcount,
                List.copyOf(participants),
                startTime,
                endTime,
                totalMinutes,
                sceneType,
                List.copyOf(segments),
                List.copyOf(constraints),
                drinkPreference,
                locationScope,
                prompt,
                parsePace(lower),
                parseBudgetLevel(lower),
                hasChild,
                childAge,
                parsePreferredTransportMode(lower),
                List.copyOf(extractPreferenceList(lower, List.of("避开", "不要", "不想要", "别去", "别安排", "avoid"))),
                List.copyOf(extractPreferenceList(lower, List.of("必须", "一定要", "想要", "最好有", "要有", "must"))),
                mentionsWeather(lower),
                detectConsultingMode(prompt)
        );
    }

    private PlanIntent normalize(PlanIntent intent, PlanIntent fallback) {
        String start = isTime(intent.startTime()) ? intent.startTime() : fallback.startTime();
        String end = isTime(intent.endTime()) ? intent.endTime() : fallback.endTime();
        int total = minutesBetween(start, end);
        int headcount = intent.headcount() > 0 ? intent.headcount() : fallback.headcount();
        List<String> participants = safeList(intent.participants(), fallback.participants());
        List<String> segments = safeList(intent.requestedSegments(), fallback.requestedSegments());
        boolean isConsultingMode = intent.isConsultingMode();
        String promptLower = safeLower(intent.originalPrompt());

        if (hasTimeInfo(promptLower) && hasHeadcountInfo(promptLower, headcount, participants)) {
            isConsultingMode = false;
        }

        boolean hasChildren = intent.hasChildren() || fallback.hasChildren()
                || participants.stream().anyMatch(p -> contains(safeLower(p), "孩子", "儿童", "娃"));

        return new PlanIntent(
                headcount,
                participants,
                start,
                end,
                total,
                text(intent.sceneType(), fallback.sceneType()),
                segments,
                safeList(intent.dietaryConstraints(), fallback.dietaryConstraints()),
                text(intent.drinkPreference(), fallback.drinkPreference()),
                text(intent.locationScope(), fallback.locationScope()),
                intent.originalPrompt(),
                enumOr(intent.pace(), fallback.pace(), PACE_VALUES, "NORMAL"),
                enumOr(intent.budgetLevel(), fallback.budgetLevel(), BUDGET_VALUES, "MEDIUM"),
                hasChildren,
                intent.childAge() != null ? intent.childAge() : fallback.childAge(),
                enumOr(intent.preferredTransportMode(), fallback.preferredTransportMode(), TRANSPORT_VALUES, "PUBLIC_TRANSIT"),
                cleanList(safeList(intent.avoid(), fallback.avoid())),
                cleanList(safeList(intent.mustHave(), fallback.mustHave())),
                intent.weatherSensitive() || fallback.weatherSensitive(),
                isConsultingMode
        );
    }

    public PlanIntent mergeForAdjustment(PlanIntent original, String adjustmentPrompt) {
        if (llmEnabled && chatModel != null) {
            try {
                PlanIntent merged = CompletableFuture
                        .supplyAsync(() -> mergeByLlm(original, adjustmentPrompt), executorService)
                        .get(llmTimeoutMs, TimeUnit.MILLISECONDS);
                if (merged != null) {
                    return intentValidator.validate(merged, adjustmentPrompt);
                }
            } catch (Exception e) {
                // fall back silently
            }
        }
        // 降级到规则 merge
        return intentValidator.validate(mergeByRules(original, adjustmentPrompt), adjustmentPrompt);
    }

    private PlanIntent mergeByLlm(PlanIntent original, String adjustmentPrompt) {
        try {
            String originalJson = objectMapper.writeValueAsString(original);
            String systemPrompt = """
                    You are a trip-planning intent merger. Output JSON only.
                    Given the ORIGINAL intent and a USER ADJUSTMENT prompt, produce a MERGED intent JSON.
                    Rules:
                    - Only override or modify fields that the user explicitly mentions or implies in the user adjustment prompt.
                    - Keep all other fields from the original intent exactly unchanged.
                    - Always output valid JSON matching the exact original structure.
                    """;
            String userPrompt = String.format("Original Intent:\n%s\n\nUser Adjustment: %s", originalJson, adjustmentPrompt);

            String content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(userPrompt)
            ))).getResult().getOutput().getText();

            JsonNode node = objectMapper.readTree(extractJson(content));
            return new PlanIntent(
                    intOr(node, "headcount", original.headcount()),
                    listOr(node, "participants", original.participants()),
                    textOr(node, "startTime", original.startTime()),
                    textOr(node, "endTime", original.endTime()),
                    intOr(node, "totalMinutes", original.totalMinutes()),
                    textOr(node, "sceneType", original.sceneType()),
                    listOr(node, "requestedSegments", original.requestedSegments()),
                    listOr(node, "dietaryConstraints", original.dietaryConstraints()),
                    textOr(node, "drinkPreference", original.drinkPreference()),
                    textOr(node, "locationScope", original.locationScope()),
                    adjustmentPrompt,
                    textOr(node, "pace", original.pace()),
                    textOr(node, "budgetLevel", original.budgetLevel()),
                    boolOr(node, "hasChildren", original.hasChildren()),
                    nullableIntOr(node, "childAge", original.childAge()),
                    textOr(node, "preferredTransportMode", original.preferredTransportMode()),
                    listOr(node, "avoid", original.avoid()),
                    listOr(node, "mustHave", original.mustHave()),
                    boolOr(node, "weatherSensitive", original.weatherSensitive()),
                    boolOr(node, "isConsultingMode", original.isConsultingMode())
            );
        } catch (Exception e) {
            log.warn("[IntentExtractor] LLM merge error: {}", e.toString());
            return original;
        }
    }

    private PlanIntent mergeByRules(PlanIntent original, String adjustmentPrompt) {
        String lower = safeLower(adjustmentPrompt);
        PlanIntent adj = extractByRules(adjustmentPrompt);

        boolean mentionsHeadcount = contains(lower, "人", "位", "一个人", "朋友", "老婆", "孩子", "情侣", "聚会", "浜", "浣", "鏈嬪弸", "瀛╁瓙");
        int headcount = mentionsHeadcount ? adj.headcount() : original.headcount();
        List<String> participants = mentionsHeadcount && !adj.participants().isEmpty() ? adj.participants() : original.participants();

        boolean mentionsTime = hasTimeInfo(lower) || contains(lower, "顺延", "延长", "延到", "推迟", "推后");
        String startTime = original.startTime();
        String endTime = original.endTime();

        if (mentionsTime) {
            List<String> times = new ArrayList<>();
            Matcher m = Pattern.compile("(\\d{1,2})[:：点](\\d{0,2})").matcher(lower);
            while (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int min = m.group(2).isBlank() ? 0 : Integer.parseInt(m.group(2));
                if (contains(lower, "晚上", "晚") && h < 12) h += 12;
                times.add(String.format(Locale.ROOT, "%02d:%02d", h, min));
            }
            if (times.isEmpty()) {
                if (contains(lower, "晚上八点", "晚八点", "8点后", "八点后", "20点")) times.add("20:00");
                else if (contains(lower, "晚上九点", "晚九点", "9点后", "九点后", "21点")) times.add("21:00");
                else if (contains(lower, "上午", "早上", "10点", "十点")) times.add("10:00");
            }

            if (times.size() >= 2) {
                startTime = times.get(0);
                endTime = times.get(1);
            } else if (times.size() == 1) {
                String singleTime = times.get(0);
                boolean isEndTime = contains(lower, "顺延", "延长", "延到", "至", "到", "结束", "玩到")
                        && !contains(lower, "开始", "出发", "从", "推迟");
                if (isEndTime) {
                    endTime = singleTime;
                } else {
                    startTime = singleTime;
                }
            } else {
                startTime = adj.startTime();
                endTime = adj.endTime();
            }
        }
        int totalMinutes = minutesBetween(startTime, endTime);

        List<String> segments;
        if (!adj.requestedSegments().isEmpty() && mentionsSegments(lower)) {
            LinkedHashSet<String> mergedSegments = new LinkedHashSet<>(original.requestedSegments());
            mergedSegments.addAll(adj.requestedSegments());
            segments = new ArrayList<>(mergedSegments);
        } else {
            segments = original.requestedSegments();
        }
        List<String> constraints = mergeConstraints(original.dietaryConstraints(), adj.dietaryConstraints());

        return new PlanIntent(
                headcount,
                participants,
                startTime,
                endTime,
                totalMinutes,
                original.sceneType(),
                segments,
                constraints,
                mentionsDrinks(lower) ? adj.drinkPreference() : original.drinkPreference(),
                mentionsLocationScope(lower) ? adj.locationScope() : original.locationScope(),
                adjustmentPrompt,
                mentionsPace(lower) ? adj.pace() : original.pace(),
                mentionsBudget(lower) ? adj.budgetLevel() : original.budgetLevel(),
                mentionsChildren(lower) ? adj.hasChildren() : original.hasChildren(),
                mentionsChildren(lower) ? adj.childAge() : original.childAge(),
                mentionsTransport(lower) ? adj.preferredTransportMode() : original.preferredTransportMode(),
                mentionsPreferenceList(lower, List.of("避开", "不要", "不想要", "别去", "别安排", "avoid")) ? adj.avoid() : original.avoid(),
                mentionsPreferenceList(lower, List.of("必须", "一定要", "想要", "最好有", "要有", "must")) ? adj.mustHave() : original.mustHave(),
                mentionsWeather(lower) ? adj.weatherSensitive() : original.weatherSensitive(),
                false
        );
    }

    private List<String> mergeConstraints(List<String> original, List<String> adjustment) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (original != null) merged.addAll(original);
        if (adjustment != null) merged.addAll(adjustment);
        return List.copyOf(merged);
    }

    private int parseHeadcount(String text) {
        if (contains(text, "一个人", "1个人", "1 人", "独自", "自己一个", "涓€涓", "鐙嚜")) return 1;
        if (contains(text, "一家三口", "三口之家", "三口人", "涓夊彛")) return 3;
        if (contains(text, "一家四口", "四口之家", "四口人", "鍥涘彛")) return 4;
        if (contains(text, "一家五口", "五口之家", "五口人")) return 5;
        if (contains(text, "双人", "夫妻", "两口子", "俩人", "俩个")) return 2;
        Matcher digit = Pattern.compile("(\\d+)\\s*(个?人|位|浜|浣)").matcher(text);
        if (digit.find()) return Integer.parseInt(digit.group(1));
        Map<String, Integer> cn = Map.of("一", 1, "二", 2, "两", 2, "三", 3, "四", 4, "五", 5, "六", 6);
        Matcher chinese = Pattern.compile("([一二两三四五六])\\s*(个)?人").matcher(text);
        if (chinese.find()) return cn.getOrDefault(chinese.group(1), 0);
        return 0;
    }

    private Integer parseChildAge(String text) {
        Matcher digit = Pattern.compile("(\\d{1,2})\\s*(岁|嵗|year|years)").matcher(text);
        if (digit.find()) return Integer.parseInt(digit.group(1));
        Map<String, Integer> cn = Map.of("一", 1, "二", 2, "两", 2, "三", 3, "四", 4, "五", 5, "六", 6, "七", 7, "八", 8, "九", 9);
        Matcher chinese = Pattern.compile("([一二两三四五六七八九])\\s*岁").matcher(text);
        if (chinese.find()) return cn.get(chinese.group(1));
        return null;
    }

    private String parseStartTime(String text) {
        if (contains(text, "晚上八点", "晚八点", "8点后", "八点后", "20点", "鏅氫笂鍏", "鏅氬叓", "8鐐")) return "20:00";
        if (contains(text, "晚上九点", "晚九点", "9点后", "九点后", "21点", "鏅氫節", "9鐐")) return "21:00";
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
        if (contains(text, "到十二点", "玩到十二点", "一直到十二点", "鍗佷簩鐐")) {
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

    private String parsePace(String text) {
        if (contains(text, "别太赶", "不要太赶", "慢一点", "轻松", "松弛", "relaxed", "slow")) return "RELAXED";
        if (contains(text, "紧凑", "多安排", "赶一点", "compact", "packed")) return "COMPACT";
        return "NORMAL";
    }

    private String parseBudgetLevel(String text) {
        if (contains(text, "便宜", "省钱", "低预算", "少花钱", "免费", "low budget", "cheap")) return "LOW";
        if (contains(text, "预算高", "贵一点", "高级", "高预算", "high budget", "premium")) return "HIGH";
        return "MEDIUM";
    }

    private String parsePreferredTransportMode(String text) {
        if (contains(text, "步行", "走路", "citywalk", "walk")) return "WALK";
        if (contains(text, "开车", "自驾", "打车", "drive", "taxi")) return "DRIVE";
        if (contains(text, "公交", "地铁", "公共交通", "public transit", "metro", "subway")) return "PUBLIC_TRANSIT";
        return "PUBLIC_TRANSIT";
    }

    private List<String> extractPreferenceList(String text, List<String> markers) {
        List<String> values = new ArrayList<>();
        for (String marker : markers) {
            int index = text.indexOf(marker.toLowerCase(Locale.ROOT));
            if (index < 0) continue;
            String tail = text.substring(index + marker.length()).trim();
            for (String token : tail.split("[，,。；;、\\s]+")) {
                String value = token.trim();
                if (!value.isBlank() && value.length() <= 16 && !markers.contains(value)) values.add(value);
                if (values.size() >= 4) return values;
            }
        }
        return values;
    }

    private boolean hasTimeInfo(String text) {
        return contains(text, "点", "到", "时", "小时", "分钟", "am", "pm", ":", "：", "下午", "晚上", "中午", "上午", "早上", "夜里", "鐐", "鍒", "鏃", "涓嬪崍", "鏅氫笂");
    }

    private boolean hasHeadcountInfo(String text, int headcount, List<String> participants) {
        return headcount > 0 || (participants != null && !participants.isEmpty())
                || contains(text, "人", "位", "独自", "自己", "情侣", "老婆", "孩子", "娃", "朋友", "聚会", "浜", "浣", "鐙嚜", "鏈嬪弸");
    }

    private boolean mentionsSegments(String text) {
        return contains(text, "吃", "饭", "餐", "喝", "bar", "玩", "活动", "电影", "散步", "鍚", "楗", "鐜");
    }

    private boolean mentionsDrinks(String text) {
        return contains(text, "bar", "酒", "喝", "清吧", "club", "精酿");
    }

    private boolean mentionsLocationScope(String text) {
        return contains(text, "远一点", "远些", "全城", "附近", "近一点", "10km", "10公里");
    }

    private boolean mentionsPace(String text) {
        return contains(text, "别太赶", "不要太赶", "慢一点", "轻松", "松弛", "紧凑", "多安排", "赶一点", "relaxed", "compact");
    }

    private boolean mentionsBudget(String text) {
        return contains(text, "预算", "便宜", "省钱", "免费", "贵一点", "高级", "low budget", "high budget", "cheap", "premium");
    }

    private boolean mentionsChildren(String text) {
        return contains(text, "孩子", "小孩", "宝宝", "娃", "亲子", "儿童", "岁");
    }

    private boolean mentionsTransport(String text) {
        return contains(text, "步行", "走路", "开车", "自驾", "打车", "公交", "地铁", "公共交通", "walk", "drive", "taxi", "metro", "subway");
    }

    private boolean mentionsWeather(String text) {
        return contains(text, "下雨", "雨天", "天气", "太晒", "怕晒", "怕冷", "室内", "weather", "rain", "indoors");
    }

    private boolean mentionsPreferenceList(String text, List<String> markers) {
        return markers.stream().anyMatch(marker -> text.contains(marker.toLowerCase(Locale.ROOT)));
    }

    private boolean detectConsultingMode(String prompt) {
        String lower = safeLower(prompt);
        boolean hasConsultKeywords = contains(lower, 
                "去哪", "推荐", "什么好", "有什么", "攻略", "好玩", "比较好", "约会去哪", "好地方", "带娃去哪", "鎺ㄨ崘", "鏀荤暐",
                "去什么", "去哪里", "地方好", "项目好", "吃什么", "玩什么", "怎么玩", "怎么安排", "如何安排", "有什么好", "有哪些", "建议"
        );
        return hasConsultKeywords && !hasTimeInfo(lower);
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
        String safe = safeLower(text);
        for (String kw : keywords) {
            if (safe.contains(kw.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }

    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private int intOr(JsonNode node, String field, int fallback) {
        return node.has(field) ? node.path(field).asInt(fallback) : fallback;
    }

    private Integer nullableIntOr(JsonNode node, String field, Integer fallback) {
        if (!node.has(field) || node.path(field).isNull()) return fallback;
        return node.path(field).asInt();
    }

    private String textOr(JsonNode node, String field, String fallback) {
        return node.has(field) ? node.path(field).asText(fallback) : fallback;
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String enumOr(String value, String fallback, Set<String> allowed, String defaultValue) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (allowed.contains(normalized)) return normalized;
        String fallbackNormalized = fallback == null ? "" : fallback.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(fallbackNormalized) ? fallbackNormalized : defaultValue;
    }

    private List<String> listOr(JsonNode node, String field, List<String> fallback) {
        if (!node.has(field) || !node.path(field).isArray()) return fallback;
        List<String> values = new ArrayList<>();
        node.path(field).forEach(item -> values.add(item.asText()));
        return values;
    }

    private List<String> safeList(List<String> value, List<String> fallback) {
        return value == null || value.isEmpty() ? (fallback == null ? List.of() : fallback) : value;
    }

    private List<String> cleanList(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(v -> !v.isBlank())
                .distinct()
                .toList();
    }

    private boolean boolOr(JsonNode node, String field, boolean fallback) {
        return node.has(field) ? node.path(field).asBoolean(fallback) : fallback;
    }
}
