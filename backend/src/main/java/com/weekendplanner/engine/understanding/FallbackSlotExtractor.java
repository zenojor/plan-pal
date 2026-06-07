package com.weekendplanner.engine.understanding;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FallbackSlotExtractor {

    private static final Pattern DIGIT_TIME = Pattern.compile("(\\d{1,2})[:：点](\\d{0,2})");
    private static final Pattern DIGIT_TIME_RANGE = Pattern.compile(
            "(\\d{1,2})[:\\uFF1A\\u70B9](\\d{0,2})\\s*(?:-|~|\\u5230|\\u81F3)\\s*(\\d{1,2})[:\\uFF1A\\u70B9](\\d{0,2})");
    private static final Pattern DIGIT_HEADCOUNT = Pattern.compile("(\\d+)\\s*(?:个)?(?:人|位|朋友)");
    private static final Pattern DIGIT_DURATION = Pattern.compile("(\\d+)\\s*(?:[-~到至]\\s*(\\d+))?\\s*(?:个)?小时");

    public TurnUnderstanding extract(UnderstandingRequest request) {
        String text = request == null || request.userTurn() == null
                ? "" : request.userTurn().trim().toLowerCase(Locale.ROOT);
        PendingAction pending = request == null ? null : request.pendingAction();
        List<com.weekendplanner.engine.candidate.CandidateSet> activeCandidates = request == null ? List.of() : request.activeCandidates();
        TurnUnderstanding.Builder builder = TurnUnderstanding.builder()
                .domainIntent(domainIntent(pending))
                .confidence(0.72)
                .reasonCode("fallback");

        if (text.isBlank()) return builder.turnIntent(TurnIntent.UNKNOWN).build();
        if (looksLikeCancel(text)) return builder.turnIntent(TurnIntent.CANCEL_PENDING).reasonCode("fallback.cancel").build();

        if (pending == null && isInitialSource(request)) {
            extractTime(text, builder);
            extractDuration(text, builder);
            extractHeadcount(text, builder);
            extractLocation(text, builder);
            extractOrderPreference(text, builder);
            extractPace(text, builder);
            extractBudget(text, builder);
            extractTransport(text, builder);
            Optional<TurnUnderstanding> initial = extractInitial(text, builder);
            if (initial.isPresent()) return initial.get();
        }

        boolean pendingNeedsHeadcount = pendingNeedsHeadcount(pending);
        Integer contextualHeadcount = pendingNeedsHeadcount ? contextualHeadcount(text) : null;
        if (contextualHeadcount != null) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, contextualHeadcount,
                    SlotProvenance.EXPLICIT, 0.98, text));
        }

        Integer selectedIndex = selectedIndex(text);
        if (selectedIndex != null && contextualHeadcount == null && !hasExplicitSlotSignal(text)) {
            return builder.turnIntent(TurnIntent.SELECT_CANDIDATE)
                    .selectedCandidateIndex(selectedIndex)
                    .reasonCode("fallback.candidate.select")
                    .build();
        }

        boolean question = looksLikeQuestion(text);
        if (question) {
            return builder.turnIntent(TurnIntent.READ_ONLY_QUESTION)
                    .readOnlyQuestion(true)
                    .reasonCode("fallback.read_only_question")
                    .build();
        }

        extractTime(text, builder);
        extractDuration(text, builder);
        extractHeadcount(text, builder);
        extractLocation(text, builder);
        extractOrderPreference(text, builder);
        extractPace(text, builder);
        extractBudget(text, builder);
        extractTransport(text, builder);

        TurnUnderstanding filled = builder.build();
        boolean movieCorrection = isMovieCorrection(pending, text, activeCandidates);
        if (filled.hasSlots() || movieCorrection) {
            String reason = movieCorrection ? "fallback.movie.correction"
                    : contextualHeadcount != null ? "fallback.contextual_headcount" : "fallback.slot.fill";
            return new TurnUnderstanding(TurnIntent.FILL_PENDING_SLOTS, filled.domainIntent(), filled.slots(),
                    filled.missingSlots(), false, null, 0.86, reason);
        }
        if (looksLikeStartNewPlan(text)) return builder.turnIntent(TurnIntent.START_NEW_PLAN).reasonCode("fallback.start_new_plan").build();
        if (looksLikeModify(text)) return builder.turnIntent(TurnIntent.MODIFY_PLAN).reasonCode("fallback.modify").build();
        return builder.turnIntent(TurnIntent.UNKNOWN).build();
    }

    public Map<String, Object> explicitSlotsFromIntent(PlanIntent intent) {
        if (intent == null) return Map.of();
        String prompt = normalize(intent.originalPrompt());
        Map<String, Object> slots = new LinkedHashMap<>();
        if (intent.headcount() > 0 && hasExplicitHeadcount(prompt)) {
            slots.put("headcount", intent.headcount());
            slots.put("explicit:headcount", true);
        }
        if (hasExplicitTime(prompt) && notBlank(intent.startTime())) {
            slots.put("startTime", intent.startTime());
            slots.put("explicit:startTime", true);
        }
        if (hasExplicitTime(prompt) && notBlank(intent.endTime())) {
            slots.put("endTime", intent.endTime());
            slots.put("explicit:endTime", true);
        }
        if (intent.totalMinutes() > 0 && hasExplicitDuration(prompt)) {
            slots.put("durationMinutes", intent.totalMinutes());
            slots.put("explicit:durationMinutes", true);
        }
        if (notBlank(intent.locationScope()) && hasExplicitLocationScope(prompt)) {
            slots.put("locationScope", intent.locationScope());
            slots.put("explicit:locationScope", true);
        }
        return Map.copyOf(slots);
    }

    public boolean looksLikeQuestion(String input) {
        String text = normalize(input);
        return containsAny(text, "\u4f60\u662f\u4ec0\u4e48\u6a21\u578b", "\u4f60\u662f\u8c01",
                "\u4f60\u80fd\u5e72\u4ec0\u4e48", "\u4f60\u80fd\u5e72\u561b", "\u4ec0\u4e48\u6a21\u578b",
                "what model", "which model", "who are you", "what can you do",
                "?", "？", "讲什么", "说什么", "是什么", "为什么", "为啥",
                "怎么样", "好看吗", "适合", "能不能", "可以吗", "what", "why", "how");
    }

    private void extractTime(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "下午", "afternoon")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "AFTERNOON", SlotProvenance.EXPLICIT, 0.95, "下午"));
        } else if (containsAny(text, "晚上", "今晚", "evening", "tonight")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "EVENING", SlotProvenance.EXPLICIT, 0.95, "晚上"));
        } else if (containsAny(text, "上午", "早上", "morning")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "MORNING", SlotProvenance.EXPLICIT, 0.95, "上午"));
        } else if (containsAny(text, "中午", "noon")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "NOON", SlotProvenance.EXPLICIT, 0.95, "中午"));
        }

        Matcher rangeMatcher = DIGIT_TIME_RANGE.matcher(text);
        if (rangeMatcher.find()) {
            int startHour = Integer.parseInt(rangeMatcher.group(1));
            int startMinute = parseMinute(rangeMatcher.group(2));
            int endHour = Integer.parseInt(rangeMatcher.group(3));
            int endMinute = parseMinute(rangeMatcher.group(4));
            if (containsAny(text, "下午", "晚上", "今晚", "pm")) {
                if (startHour < 12) startHour += 12;
                if (endHour < 12) endHour += 12;
            }
            String start = String.format(Locale.ROOT, "%02d:%02d", startHour, startMinute);
            String end = String.format(Locale.ROOT, "%02d:%02d", endHour, endMinute);
            builder.slot(SlotValue.of(SlotName.START_TIME, start,
                    SlotProvenance.EXPLICIT, 0.96, rangeMatcher.group()));
            builder.slot(SlotValue.of(SlotName.END_TIME, end,
                    SlotProvenance.EXPLICIT, 0.96, rangeMatcher.group()));
            builder.slot(SlotValue.of(SlotName.MAX_END_TIME, end,
                    SlotProvenance.EXPLICIT, 0.96, rangeMatcher.group()));
            int duration = (endHour * 60 + endMinute) - (startHour * 60 + startMinute);
            if (duration > 0) {
                builder.slot(SlotValue.durationRange(duration, duration,
                        SlotProvenance.EXPLICIT, 0.92, rangeMatcher.group()));
            }
            return;
        }

        if (containsAny(text, "十点", "10点", "10:00", "10：00")) {
            builder.slot(SlotValue.of(SlotName.START_TIME,
                    containsAny(text, "晚上", "今晚") ? "22:00" : "10:00",
                    SlotProvenance.EXPLICIT, 0.95, "十点"));
            return;
        }

        Matcher matcher = DIGIT_TIME.matcher(text);
        while (matcher.find()) {
            int hour = Integer.parseInt(matcher.group(1));
            int minute = parseMinute(matcher.group(2));
            if (containsAny(text, "下午", "晚上", "今晚", "pm") && hour < 12) hour += 12;
            builder.slot(SlotValue.of(SlotName.START_TIME,
                    String.format(Locale.ROOT, "%02d:%02d", hour, minute),
                    SlotProvenance.EXPLICIT, 0.95, matcher.group()));
            return;
        }
    }

    private void extractDuration(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "三四个小时", "三四小时")) {
            builder.slot(SlotValue.durationRange(180, 240, SlotProvenance.EXPLICIT, 0.95, "三四个小时"));
            return;
        }
        if (containsAny(text, "两三个小时", "两三小时", "二三个小时", "二三小时")) {
            builder.slot(SlotValue.durationRange(120, 180, SlotProvenance.EXPLICIT, 0.95, "两三个小时"));
            return;
        }
        if (containsAny(text, "半天")) {
            builder.slot(SlotValue.durationRange(210, 240, SlotProvenance.EXPLICIT, 0.9, "半天"));
            return;
        }
        Matcher matcher = DIGIT_DURATION.matcher(text);
        if (matcher.find()) {
            int first = Integer.parseInt(matcher.group(1));
            int second = matcher.group(2) == null || matcher.group(2).isBlank()
                    ? first : Integer.parseInt(matcher.group(2));
            builder.slot(SlotValue.durationRange(Math.min(first, second) * 60, Math.max(first, second) * 60,
                    SlotProvenance.EXPLICIT, 0.92, matcher.group()));
        }
    }

    private int parseMinute(String minuteText) {
        return minuteText == null || minuteText.isBlank() ? 0 : Integer.parseInt(minuteText);
    }

    private void extractHeadcount(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "我一个人", "一个人", "一人", "一位", "独自", "自己一个", "就我")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 1, SlotProvenance.EXPLICIT, 0.98, "一个人"));
            return;
        }
        if (containsAny(text, "我和三个朋友", "我跟三个朋友")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 4, SlotProvenance.EXPLICIT, 0.98, "我和三个朋友"));
            return;
        }
        if (containsAny(text, "三个朋友", "三位朋友", "三个人", "三人")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 3, SlotProvenance.EXPLICIT, 0.98, "三个朋友"));
            return;
        }
        if (containsAny(text, "两个朋友", "两位朋友", "两个人", "两人", "二个人")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 2, SlotProvenance.EXPLICIT, 0.98, "两个朋友"));
            return;
        }
        Matcher matcher = DIGIT_HEADCOUNT.matcher(text);
        if (matcher.find()) {
            int count = Integer.parseInt(matcher.group(1));
            if ((text.contains("我和") || text.contains("我跟")) && matcher.group(0).contains("朋友")) count++;
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, count, SlotProvenance.EXPLICIT, 0.92, matcher.group()));
        }
    }

    private void extractLocation(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "附近", "近一点", "别太远", "不要太远", "少折腾", "nearby")) {
            builder.slot(SlotValue.of(SlotName.LOCATION_SCOPE, "NEARBY", SlotProvenance.EXPLICIT, 0.9, "nearby"));
        } else if (containsAny(text, "全城", "远一点", "远些", "wide")) {
            builder.slot(SlotValue.of(SlotName.LOCATION_SCOPE, "WIDE", SlotProvenance.EXPLICIT, 0.9, "wide"));
        }
    }

    private void extractOrderPreference(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "玩完再去吃", "玩完再吃", "活动完再吃", "逛完再吃")) {
            builder.slot(SlotValue.of(SlotName.ORDER_PREFERENCE, "ACTIVITY_THEN_DINING",
                    SlotProvenance.EXPLICIT, 0.95, "玩完再吃"));
        } else if (containsAny(text, "先吃再玩", "吃完再玩", "先吃饭")) {
            builder.slot(SlotValue.of(SlotName.ORDER_PREFERENCE, "DINING_THEN_ACTIVITY",
                    SlotProvenance.EXPLICIT, 0.95, "先吃再玩"));
        }
    }

    private void extractPace(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "轻活动", "别折腾", "不要折腾", "别太久", "不太久", "轻松")) {
            builder.slot(SlotValue.of(SlotName.PACE, "RELAXED", SlotProvenance.EXPLICIT, 0.85, "relaxed"));
        }
    }

    private void extractBudget(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "预算低", "便宜", "省钱", "少花钱", "cheap")) {
            builder.slot(SlotValue.of(SlotName.BUDGET_LEVEL, "LOW", SlotProvenance.EXPLICIT, 0.85, "budget_low"));
        } else if (containsAny(text, "预算高", "高级", "贵一点", "premium")) {
            builder.slot(SlotValue.of(SlotName.BUDGET_LEVEL, "HIGH", SlotProvenance.EXPLICIT, 0.85, "budget_high"));
        }
    }

    private void extractTransport(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "步行", "走路", "walk")) {
            builder.slot(SlotValue.of(SlotName.TRANSPORT_MODE, "WALK", SlotProvenance.EXPLICIT, 0.85, "walk"));
        } else if (containsAny(text, "开车", "自驾", "打车", "drive", "taxi")) {
            builder.slot(SlotValue.of(SlotName.TRANSPORT_MODE, "DRIVE", SlotProvenance.EXPLICIT, 0.85, "drive"));
        } else if (containsAny(text, "公交", "地铁", "公共交通", "metro", "subway")) {
            builder.slot(SlotValue.of(SlotName.TRANSPORT_MODE, "PUBLIC_TRANSIT", SlotProvenance.EXPLICIT, 0.85, "public_transit"));
        }
    }

    private Optional<TurnUnderstanding> extractInitial(String text, TurnUnderstanding.Builder builder) {
        applyInitialTimeAliases(text, builder);
        applyInitialHeadcountAliases(text, builder);
        TurnUnderstanding filled = builder.build();
        boolean hasTime = filled.slot(SlotName.START_TIME).isPresent()
                || filled.slot(SlotName.END_TIME).isPresent()
                || filled.slot(SlotName.MAX_END_TIME).isPresent()
                || filled.slot(SlotName.TIME_RANGE).isPresent();
        boolean hasHeadcount = filled.slot(SlotName.HEADCOUNT).isPresent();
        boolean hasLocationScope = filled.slot(SlotName.LOCATION_SCOPE).isPresent();
        if (containsAny(text, "奶茶", "冰沙", "果汁", "咖啡", "甜品", "好喝", "饮品",
                "milk tea", "bubble tea", "smoothie", "juice", "coffee", "dessert")
                && !containsAny(text, "清吧", "酒吧", "精酿", "小酒馆", "pub", "bar", "酒", "club", "livehouse", "娓呭惂", "楦″尾閰", "绮鹃吙")) {
            return Optional.of(initial(builder, TurnIntent.TRIP_RESEARCH, DomainIntent.PRODUCT,
                    RouteTarget.RESEARCH, 0.86, "fallback.initial.product"));
        }
        boolean hasTripContent = containsAny(text, "吃", "餐厅", "饭", "活动", "散步", "玩", "电影", "附近",
                "dining", "activity", "restaurant", "movie", "food",
                "鍚冮キ", "椁愬巺", "娲诲姩", "鏁ｆ", "鐜?", "闄勮繎");

        if (containsAny(text, "电影", "影院", "场次", "movie", "cinema", "鐢靛奖", "褰遍櫌")) {
            return Optional.of(initial(builder, TurnIntent.TRIP_RESEARCH, DomainIntent.MOVIE,
                    RouteTarget.RESEARCH, 0.86, "fallback.initial.movie"));
        }
        if (containsAny(text, "附近有什么吃", "有什么吃", "吃的", "餐厅", "饭店", "restaurant", "food",
                "闄勮繎", "鍚冪殑", "椁愬巺", "楗簵")) {
            return Optional.of(initial(builder, TurnIntent.TRIP_RESEARCH, DomainIntent.DINING,
                    RouteTarget.RESEARCH, 0.84, "fallback.initial.dining"));
        }
        if (containsAny(text, "第一次约会", "约会", "去哪", "推荐", "灵感", "适合", "主意", "ideas",
                "date ideas", "绗竴娆", "绾︿細", "鎺ㄨ崘")) {
            return Optional.of(initial(builder, TurnIntent.TRIP_IDEA, DomainIntent.GENERIC_TRIP,
                    RouteTarget.CONSULT, 0.78, "fallback.initial.trip_idea"));
        }
        if (hasTime && (hasHeadcount || hasTripContent) && containsAny(text, "吃饭", "活动", "散步", "行程", "安排", "计划",
                "餐厅", "电影", "玩", "itinerary", "schedule", "plan",
                "鍚冮キ", "娲诲姩", "鏁ｆ", "琛岀▼", "瀹夋帓")) {
            return Optional.of(initial(builder, TurnIntent.PLAN_BUILD, DomainIntent.GENERIC_TRIP,
                    RouteTarget.PLAN, 0.86, "fallback.initial.plan_build"));
        }
        if (hasTime && hasTripContent) {
            return Optional.of(initial(builder, TurnIntent.PLAN_BUILD, DomainIntent.GENERIC_TRIP,
                    RouteTarget.PLAN, 0.78, "fallback.initial.timed_trip"));
        }
        if (!hasTime && hasHeadcount && hasLocationScope && hasTripContent) {
            return Optional.of(initial(builder, TurnIntent.PLAN_BUILD, DomainIntent.GENERIC_TRIP,
                    RouteTarget.PLAN, 0.78, "fallback.initial.constrained_trip_plan"));
        }
        if (hasTime && containsAny(text, ":", "：")) {
            return Optional.of(initial(builder, TurnIntent.PLAN_BUILD, DomainIntent.GENERIC_TRIP,
                    RouteTarget.PLAN, 0.68, "fallback.initial.clock_time"));
        }
        if (containsAny(text, "安排", "规划", "计划", "完整行程", "生成方案", "帮我安排",
                "plan", "itinerary", "schedule", "瀹夋帓", "瑙勫垝", "璁″垝", "琛岀▼")) {
            return Optional.of(initial(builder, TurnIntent.ASK_CLARIFICATION, DomainIntent.GENERIC_TRIP,
                    RouteTarget.CLARIFY, 0.78, "fallback.initial.clarify"));
        }
        if (looksLikeQuestion(text)) {
            return Optional.of(initial(builder, TurnIntent.GENERAL_QA, DomainIntent.NON_TRIP,
                    RouteTarget.QA, 0.72, "fallback.initial.general_qa"));
        }
        if (containsAny(text, "谢谢", "谢了", "随便聊聊", "讲个笑话", "你好", "hello", "thanks")) {
            return Optional.of(initial(builder, TurnIntent.SMALLTALK, DomainIntent.NON_TRIP,
                    RouteTarget.QA, 0.72, "fallback.initial.smalltalk"));
        }
        return Optional.empty();
    }

    private TurnUnderstanding initial(TurnUnderstanding.Builder builder,
                                      TurnIntent turnIntent,
                                      DomainIntent domainIntent,
                                      RouteTarget routeTarget,
                                      double confidence,
                                      String reasonCode) {
        TurnUnderstanding filled = builder.build();
        return new TurnUnderstanding(turnIntent, domainIntent, routeTarget, filled.slots(), filled.missingSlots(),
                turnIntent == TurnIntent.GENERAL_QA || turnIntent == TurnIntent.READ_ONLY_QUESTION,
                filled.selectedCandidateIndex(), confidence, reasonCode);
    }

    private void applyInitialTimeAliases(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "下午两点", "下午2点", "下午 2 点", "2pm")) {
            builder.slot(SlotValue.of(SlotName.START_TIME, "14:00", SlotProvenance.EXPLICIT, 0.95, "afternoon two"));
        } else if (containsAny(text, "上午十点", "上午10点", "上午 10 点", "10am")) {
            builder.slot(SlotValue.of(SlotName.START_TIME, "10:00", SlotProvenance.EXPLICIT, 0.95, "morning ten"));
        } else if (containsAny(text, "下午", "afternoon")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "AFTERNOON", SlotProvenance.EXPLICIT, 0.9, "afternoon"));
        } else if (containsAny(text, "晚上", "今晚", "evening", "tonight")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "EVENING", SlotProvenance.EXPLICIT, 0.9, "evening"));
        } else if (containsAny(text, "上午", "早上", "morning")) {
            builder.slot(SlotValue.of(SlotName.TIME_RANGE, "MORNING", SlotProvenance.EXPLICIT, 0.9, "morning"));
        }
    }

    private void applyInitialHeadcountAliases(String text, TurnUnderstanding.Builder builder) {
        if (containsAny(text, "一个人", "我一个人", "就我", "solo")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 1, SlotProvenance.EXPLICIT, 0.95, "one person"));
        } else if (containsAny(text, "一家三口")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 3, SlotProvenance.EXPLICIT, 0.95, "family of three"));
        } else if (containsAny(text, "孩子和朋友", "小孩和朋友", "带孩子和朋友")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 3, SlotProvenance.IMPLIED, 0.8, "child and friend"));
        } else if (containsAny(text, "两个人", "2个人", "情侣")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 2, SlotProvenance.EXPLICIT, 0.95, "two people"));
        } else if (containsAny(text, "我和三个朋友")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 4, SlotProvenance.EXPLICIT, 0.95, "me and three friends"));
        } else if (containsAny(text, "三个人", "3个人", "三个朋友")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 3, SlotProvenance.EXPLICIT, 0.95, "three people"));
        } else if (containsAny(text, "one person")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 1, SlotProvenance.EXPLICIT, 0.95, "one person"));
        } else if (containsAny(text, "two people", "2 people")) {
            builder.slot(SlotValue.of(SlotName.HEADCOUNT, 2, SlotProvenance.EXPLICIT, 0.95, "two people"));
        }
    }

    private boolean isInitialSource(UnderstandingRequest request) {
        return request != null && "initial".equalsIgnoreCase(request.source());
    }

    private DomainIntent domainIntent(PendingAction pending) {
        if (pending == null) return DomainIntent.UNKNOWN;
        if ("MOVIE".equalsIgnoreCase(pending.workflowType()) || "MOVIE_SCHEDULING".equalsIgnoreCase(pending.type())) {
            return DomainIntent.MOVIE;
        }
        if ("DINING_LOCKED_PLAN".equalsIgnoreCase(pending.workflowType()) || "PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())) {
            return DomainIntent.DINING_LOCKED_PLAN;
        }
        if ("CONTEXTUAL_RESEARCH".equalsIgnoreCase(pending.workflowType())) return DomainIntent.CONTEXTUAL_RESEARCH;
        return DomainIntent.UNKNOWN;
    }

    private boolean isMovieCorrection(PendingAction pending, String text, List<CandidateSet> activeCandidates) {
        if (!text.contains("电影")) return false;
        boolean correctionWording = containsAny(text, "我说", "说的是", "不是", "刚才", "前面", "电影呀", "电影啊");
        boolean moviePending = pending != null && ("MOVIE".equalsIgnoreCase(pending.workflowType())
                || "MOVIE_SCHEDULING".equalsIgnoreCase(pending.type()));
        boolean recentMovie = activeCandidates != null
                && activeCandidates.stream().anyMatch(set -> "MOVIE".equalsIgnoreCase(set.type()));
        return correctionWording && (moviePending || recentMovie);
    }

    private boolean pendingNeedsHeadcount(PendingAction pending) {
        if (pending == null) return false;
        if (pending.collectedSlots() != null && pending.collectedSlots().containsKey("headcount")) return false;
        boolean slotRequired = pending.requiredSlots() != null
                && pending.requiredSlots().stream().anyMatch(slot -> "headcount".equalsIgnoreCase(slot));
        boolean slotExpected = pending.expectedReplies() != null
                && pending.expectedReplies().stream().anyMatch(reply -> "headcount".equalsIgnoreCase(reply));
        boolean slotWorkflow = "MOVIE_SCHEDULING".equalsIgnoreCase(pending.type())
                || "INITIAL_PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())
                || "PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())
                || "MOVIE".equalsIgnoreCase(pending.workflowType())
                || "INITIAL_PLAN".equalsIgnoreCase(pending.workflowType())
                || "DINING_LOCKED_PLAN".equalsIgnoreCase(pending.workflowType());
        return slotWorkflow && (slotRequired || slotExpected);
    }

    private Integer contextualHeadcount(String text) {
        String compact = stripReplyParticles(compact(text));
        compact = stripPrefix(compact, "一共", "总共", "大概", "大约", "就");
        return switch (compact) {
            case "1", "1个", "1人", "1位", "一", "一个", "一人", "一位", "一个人",
                    "我", "我自己", "自己", "独自", "solo" -> 1;
            case "2", "2个", "2人", "2位", "二", "二个", "二人", "二位",
                    "两", "两个", "两人", "两位", "两个人" -> 2;
            case "3", "3个", "3人", "3位", "三", "三个", "三人", "三位", "三个人" -> 3;
            case "4", "4个", "4人", "4位", "四", "四个", "四人", "四位", "四个人" -> 4;
            case "5", "5个", "5人", "5位", "五", "五个", "五人", "五位", "五个人" -> 5;
            case "6", "6个", "6人", "6位", "六", "六个", "六人", "六位", "六个人" -> 6;
            default -> contextualDigitHeadcount(compact);
        };
    }

    private Integer contextualDigitHeadcount(String compact) {
        Matcher matcher = Pattern.compile("^(\\d{1,2})(?:个|人|位)?$").matcher(compact);
        if (!matcher.find()) {
            Matcher embedded = DIGIT_HEADCOUNT.matcher(compact);
            if (!embedded.find()) return null;
            int count = Integer.parseInt(embedded.group(1));
            return count > 0 ? count : null;
        }
        int count = Integer.parseInt(matcher.group(1));
        return count > 0 ? count : null;
    }

    private String compact(String input) {
        return normalize(input).replaceAll("[\\s，,。.!！?？]", "");
    }

    private String stripReplyParticles(String compact) {
        String value = compact;
        while (value.endsWith("吧") || value.endsWith("啊") || value.endsWith("呀")
                || value.endsWith("哦") || value.endsWith("啦") || value.endsWith("了")
                || value.endsWith("呢")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private String stripPrefix(String compact, String... prefixes) {
        String value = compact;
        boolean stripped;
        do {
            stripped = false;
            for (String prefix : prefixes) {
                if (value.startsWith(prefix) && value.length() > prefix.length()) {
                    value = value.substring(prefix.length());
                    stripped = true;
                }
            }
        } while (stripped);
        return value;
    }

    private Integer selectedIndex(String text) {
        if (containsAny(text, "第一个", "第1个", "一号", "1号", "选一", "选第一个", "第一个吧", "first", "选这个", "就这个")) return 1;
        if (containsAny(text, "第二个", "第2个", "二号", "2号", "选二", "选第二个", "第二个吧", "second")) return 2;
        if (containsAny(text, "第三个", "第3个", "三号", "3号", "选三", "选第三个", "第三个吧", "third")) return 3;
        Matcher matcher = Pattern.compile("(^|\\D)([123])(\\D|$)").matcher(text);
        return matcher.find() ? Integer.parseInt(matcher.group(2)) : null;
    }

    private boolean looksLikeCancel(String text) {
        return containsAny(text, "取消", "算了", "先不用");
    }

    private boolean looksLikeStartNewPlan(String text) {
        return containsAny(text, "重新规划", "新计划", "换个计划", "重新来");
    }

    private boolean looksLikeModify(String text) {
        return containsAny(text, "换一个", "换个", "换一批", "不要这个", "太远", "近一点", "重新推荐",
                "延长", "延到", "到晚上", "预算", "便宜", "省钱");
    }

    private boolean hasExplicitHeadcount(String text) {
        return containsAny(text, "人", "位", "朋友", "孩子", "娃", "情侣", "同学", "同事", "聚会", "团建");
    }

    private boolean hasExplicitTime(String text) {
        return containsAny(text, "点", "分", "时", "am", "pm", "：", ":", "下午", "晚上",
                "中午", "上午", "早上", "今晚", "今天", "明天", "周", "星期");
    }

    private boolean hasExplicitDuration(String text) {
        return containsAny(text, "小时", "分钟", "半天", "全天", "三四", "两三", "二三", "4h", "3h", "2h");
    }

    private boolean hasExplicitLocationScope(String text) {
        return containsAny(text, "附近", "近一点", "别太远", "不要太远", "本地", "全城", "远一点", "远些");
    }

    private boolean hasExplicitSlotSignal(String text) {
        return hasExplicitTime(text)
                || hasExplicitHeadcount(text)
                || hasExplicitDuration(text)
                || hasExplicitLocationScope(text);
    }

    private boolean containsAny(String text, String... values) {
        for (String value : values) {
            if (text.contains(value.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String normalize(String input) {
        return input == null ? "" : input.trim().toLowerCase(Locale.ROOT);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
