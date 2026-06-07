package com.weekendplanner.engine.patch;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.SlotValue;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
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
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
public class PlanPatchExtractor {

    private static final Set<String> EDIT_TYPES = Set.of(
            "REPLACE", "DELETE", "ADD", "RELAX", "TIGHTEN", "TIME_SHIFT", "KEEP_AND_REPLAN", "REORDER");
    private static final Set<String> PHASES = Set.of("ACTIVITY", "DINING", "DRINKS", "LEISURE");
    private static final Set<String> TIME_RANGES = Set.of("MORNING", "NOON", "AFTERNOON", "EVENING", "NIGHT");
    private static final Set<String> PACE_VALUES = Set.of("RELAXED", "NORMAL", "COMPACT");
    private static final Set<String> BUDGET_VALUES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> TRANSPORT_VALUES = Set.of("WALK", "DRIVE", "PUBLIC_TRANSIT");

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    @Value("${agent.patch.llm-enabled:true}")
    private boolean llmEnabled = true;

    @Value("${agent.patch.timeout-ms:2000}")
    private long llmTimeoutMs = 2000;

    @Autowired
    public PlanPatchExtractor(ObjectProvider<ChatModel> chatModelProvider, ObjectMapper objectMapper) {
        this(chatModelProvider.getIfAvailable(), objectMapper);
    }

    public PlanPatchExtractor(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatModel = chatModel;
        this.objectMapper = objectMapper;
    }

    public PlanPatch extract(String feedback, List<PlanStep> timeline, PlanIntent originalIntent) {
        PlanPatch fallback = normalize(extractByRules(feedback), fallbackPatch());
        if (!llmEnabled || chatModel == null) {
            return fallback;
        }

        try {
            return CompletableFuture.supplyAsync(() -> extractByLlm(feedback, timeline, originalIntent, fallback))
                    .get(llmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    public PlanPatch extract(String feedback,
                             List<PlanStep> timeline,
                             PlanIntent originalIntent,
                             TurnUnderstanding understanding) {
        if (understanding != null && !canProducePatch(understanding)) {
            return fallbackPatch();
        }
        PlanPatch fallback = normalize(applyUnderstandingSlots(extractByRules(feedback), understanding), fallbackPatch());
        if (!llmEnabled || chatModel == null) {
            return fallback;
        }

        try {
            return CompletableFuture.supplyAsync(() -> extractByLlm(feedback, timeline, originalIntent, fallback))
                    .get(llmTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return fallback;
        }
    }

    private boolean canProducePatch(TurnUnderstanding understanding) {
        if (understanding.turnIntent() == TurnIntent.MODIFY_PLAN) return true;
        if (understanding.turnIntent() == TurnIntent.FILL_PENDING_SLOTS) return true;
        if (understanding.turnIntent() == TurnIntent.REFINE_CANDIDATES) return true;
        if (understanding.turnIntent() == TurnIntent.UNKNOWN) return true;
        return false;
    }

    private PlanPatch extractByLlm(String feedback, List<PlanStep> timeline, PlanIntent originalIntent, PlanPatch fallback) {
        try {
            String schema = """
                    You are a plan-edit intent extractor. Output JSON only.
                    You may not output a new timeline. Only output a PlanPatch.
                    Schema:
                    intent: MODIFY_PLAN
                    editType: REPLACE|DELETE|ADD|RELAX|TIGHTEN|TIME_SHIFT|KEEP_AND_REPLAN|REORDER
                    target: {
                      segmentId:string|null,
                      timeRange:MORNING|NOON|AFTERNOON|EVENING|NIGHT|null,
                      activityType:string|null,
                      phase:ACTIVITY|DINING|DRINKS|LEISURE|null,
                      anchorSegmentId:string|null,
                      position:BEFORE|AFTER|START|END|null
                    }
                    requirements: {
                      keep:string[],
                      avoid:string[],
                      prefer:string[],
                      pace:RELAXED|NORMAL|COMPACT|null,
                      budgetLevel:LOW|MEDIUM|HIGH|null,
                      preferredTransportMode:WALK|DRIVE|PUBLIC_TRANSIT|null,
                      endEarlier:boolean
                    }
                    requiresSearch:boolean
                    Map restaurants/dining to DINING. Map bars/drinks to DRINKS. Map activities to ACTIVITY.
                    Map cuisine or dish requests like "hotpot", "火锅", "bbq", "烧烤", "小龙虾", "吃辣" to DINING
                    with the corresponding prefer tag, and set requiresSearch=true.
                    If the user says they want to eat something in a time range, such as "晚上想吃火锅",
                    prefer ADD with target.timeRange=EVENING, target.phase=DINING, requirements.prefer=["hotpot"].
                    If the user cancels drinks (for example: "don't drink", "no drinks", "算了不喝酒了"), prefer DELETE with phase DRINKS.
                    """;
            String user = "Current intent:\n" + objectMapper.writeValueAsString(originalIntent)
                    + "\nCurrent timeline:\n" + summarizeTimeline(timeline)
                    + "\nUser feedback:\n" + (feedback == null ? "" : feedback);
            String content = chatModel.call(new Prompt(List.of(
                    new SystemMessage(schema),
                    new UserMessage(user)
            ))).getResult().getOutput().getText();
            JsonNode node = objectMapper.readTree(extractJson(content));
            PlanPatch llmPatch = new PlanPatch(
                    textOr(node, "intent", "MODIFY_PLAN"),
                    textOr(node, "editType", fallback.editType()),
                    parseTarget(node.path("target"), fallback.target()),
                    parseRequirements(node.path("requirements"), fallback.requirements()),
                    node.has("requiresSearch") ? node.path("requiresSearch").asBoolean(fallback.requiresSearch()) : fallback.requiresSearch()
            );
            return mergeExplicitFallback(normalize(llmPatch, fallback), fallback);
        } catch (Exception e) {
            return fallback;
        }
    }

    private PlanPatch mergeExplicitFallback(PlanPatch patch, PlanPatch fallback) {
        if (patch == null || fallback == null) return patch == null ? fallback : patch;
        PlanPatch.Target target = patch.target();
        PlanPatch.Target fallbackTarget = fallback.target();
        PlanPatch.Requirements req = patch.requirements();
        PlanPatch.Requirements fallbackReq = fallback.requirements();

        boolean cuisineFallback = containsToken(fallbackReq.prefer(), "HOTPOT", "BBQ", "CRAYFISH", "SPICY");
        String phase = target.phase();
        String activityType = target.activityType();
        if (cuisineFallback && fallbackTarget.phase() != null) {
            phase = fallbackTarget.phase();
            activityType = fallbackTarget.activityType();
        }

        String editType = patch.editType();
        if (fallback.requiresSearch() && "KEEP_AND_REPLAN".equalsIgnoreCase(editType)) {
            editType = fallback.editType();
        }

        return new PlanPatch(
                patch.intent(),
                editType,
                new PlanPatch.Target(target.segmentId(),
                        firstNonBlank(target.timeRange(), fallbackTarget.timeRange()),
                        firstNonBlank(activityType, fallbackTarget.activityType()),
                        firstNonBlank(phase, fallbackTarget.phase()),
                        target.anchorSegmentId(),
                        target.position()),
                new PlanPatch.Requirements(
                        mergeTokens(req.keep(), fallbackReq.keep()),
                        mergeTokens(req.avoid(), fallbackReq.avoid()),
                        mergeTokens(req.prefer(), fallbackReq.prefer()),
                        firstNonBlank(req.pace(), fallbackReq.pace()),
                        firstNonBlank(req.budgetLevel(), fallbackReq.budgetLevel()),
                        firstNonBlank(req.preferredTransportMode(), fallbackReq.preferredTransportMode()),
                        req.endEarlier() || fallbackReq.endEarlier()),
                patch.requiresSearch() || fallback.requiresSearch()
        );
    }

    private PlanPatch extractByRules(String feedback) {
        String lower = safeLower(feedback);
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        LinkedHashSet<String> avoid = new LinkedHashSet<>();
        LinkedHashSet<String> prefer = new LinkedHashSet<>();

        if (contains(lower, "餐厅别换", "餐厅不要换", "吃饭别换", "饭店别换", "keep restaurant", "keep dining")) {
            keep.add("DINING");
        }
        if (contains(lower, "保留餐厅", "保留吃饭", "保留饭店")) {
            keep.add("DINING");
        }
        if (contains(lower, "保留活动", "活动别换")) {
            keep.add("ACTIVITY");
        }
        if (contains(lower, "别换", "不要换", "保留") && contains(lower, "bar", "酒吧", "喝酒", "小酌")) {
            keep.add("DRINKS");
        }

        if (contains(lower, "商场", "mall")) avoid.add("MALL");
        if (contains(lower, "室外", "户外", "outdoor")) avoid.add("OUTDOOR");
        if (contains(lower, "club", "夜店")) avoid.add("CLUB");

        if (contains(lower, "室内", "indoor")) prefer.add("INDOOR");
        if (contains(lower, "安静", "quiet")) prefer.add("QUIET");
        if (contains(lower, "小孩", "孩子", "儿童", "亲子", "kid", "child")) prefer.add("CHILD_FRIENDLY");
        if (contains(lower, "近", "别太远", "不要太远", "near", "shorter distance")) prefer.add("NEARBY");
        if (contains(lower, "公园", "park")) prefer.add("PARK");
        boolean wantsDining = false;
        boolean hasCuisinePreference = false;
        if (contains(lower, "火锅", "hotpot", "涮锅", "涮肉")) {
            prefer.add("HOTPOT");
            wantsDining = true;
            hasCuisinePreference = true;
        }
        if (contains(lower, "烧烤", "烤肉", "bbq", "barbecue")) {
            prefer.add("BBQ");
            wantsDining = true;
            hasCuisinePreference = true;
        }
        if (contains(lower, "小龙虾", "crayfish")) {
            prefer.add("CRAYFISH");
            wantsDining = true;
            hasCuisinePreference = true;
        }
        if (contains(lower, "川菜", "湘菜", "吃辣", "辣", "spicy", "sichuan", "hunan")) {
            prefer.add("SPICY");
            wantsDining = true;
            hasCuisinePreference = true;
        }
        if (hasCuisinePreference) {
            prefer.add("STRICT_TAGS");
        }

        boolean cancelDrinks = contains(lower,
                "不喝酒", "别喝酒", "不要喝酒", "先不喝酒", "算了不喝酒",
                "no drinks", "skip drinks", "without drinks");

        String editType = "KEEP_AND_REPLAN";
        boolean requiresSearch = false;
        if (cancelDrinks || contains(lower, "删掉", "删除", "去掉", "不要安排", "remove", "delete")) {
            editType = "DELETE";
        }
        if (contains(lower, "换成", "替换", "改成", "换个", "replace")) {
            editType = "REPLACE";
            requiresSearch = true;
        }
        if (contains(lower, "增加", "加一个", "加点", "再安排", "add")) {
            editType = "ADD";
            requiresSearch = true;
        }
        if (contains(lower, "太累", "少安排", "轻松", "松一点", "别太赶", "relax")) {
            editType = "RELAX";
        }
        if (contains(lower, "紧凑", "多安排", "排满", "tight", "compact")) {
            editType = "TIGHTEN";
        }
        if (wantsDining && "KEEP_AND_REPLAN".equals(editType)) {
            editType = "ADD";
            requiresSearch = true;
        }

        boolean endEarlier = contains(lower, "早点结束", "提前结束", "结束早点", "早一点结束", "end earlier");
        if (endEarlier && "KEEP_AND_REPLAN".equals(editType)) {
            editType = "TIME_SHIFT";
        }

        String timeRange = null;
        if (contains(lower, "上午", "morning")) timeRange = "MORNING";
        else if (contains(lower, "中午", "午饭", "noon")) timeRange = "NOON";
        else if (contains(lower, "下午", "afternoon")) timeRange = "AFTERNOON";
        else if (contains(lower, "晚上", "晚间", "evening")) timeRange = "EVENING";
        else if (contains(lower, "夜里", "深夜", "night")) timeRange = "NIGHT";

        String phase = null;
        if (cancelDrinks || contains(lower, "bar", "酒吧", "喝酒", "小酌")) phase = "DRINKS";
        else if (wantsDining || contains(lower, "餐厅", "饭店", "吃饭", "想吃", "dining", "restaurant")) phase = "DINING";
        else if (contains(lower, "活动", "玩", "景点", "项目", "activity")) phase = "ACTIVITY";

        String pace = contains(lower, "太累", "轻松", "少安排", "别太赶", "relax") ? "RELAXED"
                : contains(lower, "紧凑", "多安排", "排满", "compact") ? "COMPACT" : null;
        String budget = contains(lower, "便宜", "省钱", "低预算", "cheap") ? "LOW"
                : contains(lower, "贵", "高级", "高预算", "premium") ? "HIGH" : null;
        String transport = contains(lower, "步行", "走路", "walk") ? "WALK"
                : contains(lower, "打车", "开车", "drive", "taxi") ? "DRIVE" : null;

        return new PlanPatch(
                "MODIFY_PLAN",
                editType,
                new PlanPatch.Target(null, timeRange, phase, phase),
                new PlanPatch.Requirements(List.copyOf(keep), List.copyOf(avoid), List.copyOf(prefer),
                        pace, budget, transport, endEarlier),
                requiresSearch
        );
    }

    private PlanPatch normalize(PlanPatch patch, PlanPatch fallback) {
        String editType = enumOr(patch.editType(), fallback.editType(), EDIT_TYPES, "KEEP_AND_REPLAN");
        PlanPatch.Target target = patch.target() == null ? fallback.target() : patch.target();
        String timeRange = enumOrNullable(target.timeRange(), TIME_RANGES);
        String phase = enumOrNullable(target.phase(), PHASES);
        String activityType = enumOrNullable(target.activityType(), PHASES);
        String position = target.position() == null ? null : target.position().trim().toUpperCase(Locale.ROOT);
        if (position == null || !Set.of("BEFORE", "AFTER", "START", "END").contains(position)) {
            position = null;
        }

        PlanPatch.Requirements req = patch.requirements() == null ? fallback.requirements() : patch.requirements();
        return new PlanPatch(
                "MODIFY_PLAN",
                editType,
                new PlanPatch.Target(blankToNull(target.segmentId()), timeRange, activityType, phase,
                        blankToNull(target.anchorSegmentId()), position),
                new PlanPatch.Requirements(cleanTokens(req.keep()), cleanTokens(req.avoid()), cleanTokens(req.prefer()),
                        enumOrNullable(req.pace(), PACE_VALUES),
                        enumOrNullable(req.budgetLevel(), BUDGET_VALUES),
                        enumOrNullable(req.preferredTransportMode(), TRANSPORT_VALUES),
                        req.endEarlier()),
                patch.requiresSearch() || "REPLACE".equals(editType) || "ADD".equals(editType)
        );
    }

    private PlanPatch applyUnderstandingSlots(PlanPatch patch, TurnUnderstanding understanding) {
        if (understanding == null || !understanding.hasSlots()) return patch;
        PlanPatch.Target target = patch.target();
        PlanPatch.Requirements req = patch.requirements();
        String timeRange = understanding.slot(SlotName.TIME_RANGE)
                .map(SlotValue::value)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse(target.timeRange());
        String pace = understanding.slot(SlotName.PACE)
                .map(SlotValue::value)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse(req.pace());
        String budget = understanding.slot(SlotName.BUDGET_LEVEL)
                .map(SlotValue::value)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse(req.budgetLevel());
        String transport = understanding.slot(SlotName.TRANSPORT_MODE)
                .map(SlotValue::value)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .orElse(req.preferredTransportMode());
        List<String> prefer = new ArrayList<>(req.prefer());
        understanding.slot(SlotName.LOCATION_SCOPE)
                .map(SlotValue::value)
                .map(String::valueOf)
                .map(value -> value.toUpperCase(Locale.ROOT))
                .filter(value -> !prefer.contains(value))
                .ifPresent(prefer::add);
        return new PlanPatch(patch.intent(), patch.editType(),
                new PlanPatch.Target(target.segmentId(), timeRange, target.activityType(), target.phase(),
                        target.anchorSegmentId(), target.position()),
                new PlanPatch.Requirements(req.keep(), req.avoid(), prefer, pace, budget, transport, req.endEarlier()),
                patch.requiresSearch());
    }

    private PlanPatch.Target parseTarget(JsonNode node, PlanPatch.Target fallback) {
        if (node == null || !node.isObject()) return fallback;
        return new PlanPatch.Target(
                textOr(node, "segmentId", fallback.segmentId()),
                textOr(node, "timeRange", fallback.timeRange()),
                textOr(node, "activityType", fallback.activityType()),
                textOr(node, "phase", fallback.phase()),
                textOr(node, "anchorSegmentId", fallback.anchorSegmentId()),
                textOr(node, "position", fallback.position())
        );
    }

    private PlanPatch.Requirements parseRequirements(JsonNode node, PlanPatch.Requirements fallback) {
        if (node == null || !node.isObject()) return fallback;
        return new PlanPatch.Requirements(
                listOr(node, "keep", fallback.keep()),
                listOr(node, "avoid", fallback.avoid()),
                listOr(node, "prefer", fallback.prefer()),
                textOr(node, "pace", fallback.pace()),
                textOr(node, "budgetLevel", fallback.budgetLevel()),
                textOr(node, "preferredTransportMode", fallback.preferredTransportMode()),
                node.has("endEarlier") ? node.path("endEarlier").asBoolean(fallback.endEarlier()) : fallback.endEarlier()
        );
    }

    private String summarizeTimeline(List<PlanStep> timeline) {
        if (timeline == null || timeline.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (PlanStep step : timeline) {
            if (step == null || step.isTransit()) continue;
            sb.append("- ")
                    .append(step.startTime()).append("-").append(step.endTime()).append(" ")
                    .append(step.phase()).append(" ")
                    .append(step.poiName()).append(" (POI ID: ")
                    .append(step.poiId()).append(", Segment ID: ")
                    .append(step.segmentId()).append(")\n");
        }
        return sb.toString();
    }

    private PlanPatch fallbackPatch() {
        return new PlanPatch("MODIFY_PLAN", "KEEP_AND_REPLAN",
                new PlanPatch.Target(null, null, null, null, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                false);
    }

    private List<String> listOr(JsonNode node, String field, List<String> fallback) {
        if (!node.has(field) || !node.path(field).isArray()) return fallback;
        List<String> values = new ArrayList<>();
        node.path(field).forEach(item -> values.add(item.asText()));
        return values;
    }

    private String textOr(JsonNode node, String field, String fallback) {
        return node != null && node.has(field) && !node.path(field).isNull()
                ? node.path(field).asText(fallback) : fallback;
    }

    private String enumOr(String value, String fallback, Set<String> allowed, String defaultValue) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (allowed.contains(normalized)) return normalized;
        String fallbackNormalized = fallback == null ? "" : fallback.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(fallbackNormalized) ? fallbackNormalized : defaultValue;
    }

    private String enumOrNullable(String value, Set<String> allowed) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(normalized) ? normalized : null;
    }

    private List<String> cleanTokens(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .map(this::blankToNull)
                .filter(v -> v != null)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private List<String> mergeTokens(List<String> primary, List<String> fallback) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (primary != null) primary.stream()
                .map(this::blankToNull)
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .forEach(merged::add);
        if (fallback != null) fallback.stream()
                .map(this::blankToNull)
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .forEach(merged::add);
        return List.copyOf(merged);
    }

    private boolean containsToken(List<String> values, String... tokens) {
        if (values == null || values.isEmpty()) return false;
        Set<String> normalized = values.stream()
                .map(this::blankToNull)
                .filter(value -> value != null)
                .map(value -> value.trim().toUpperCase(Locale.ROOT))
                .collect(java.util.stream.Collectors.toSet());
        for (String token : tokens) {
            if (normalized.contains(token.toUpperCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String extractJson(String text) {
        if (text == null) return "{}";
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : text;
    }

    private boolean contains(String text, String... keywords) {
        String safe = safeLower(text);
        for (String keyword : keywords) {
            if (safe.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String safeLower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
