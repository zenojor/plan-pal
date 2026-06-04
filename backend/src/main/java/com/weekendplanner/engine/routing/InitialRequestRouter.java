package com.weekendplanner.engine.routing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InitialRequestRouter {

    private static final Pattern HHMM = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})");
    private static final Pattern RANGE = Pattern.compile("(\\d{1,2})\\s*[:：]?\\s*(\\d{0,2})\\s*[-到至~]\\s*(\\d{1,2})");
    private static final Pattern HOUR_ONLY = Pattern.compile("(\\d{1,2})\\s*(点|時|时|o'clock)");
    private static final Pattern HEADCOUNT = Pattern.compile("(\\d+)\\s*(个人|人|位|persons?|people)");
    private static final Pattern DURATION = Pattern.compile("(\\d+)\\s*(小时|鐘頭|钟头|hours?|hrs?)");

    private static final List<String> PLAN_KEYWORDS = List.of(
            "安排", "规划", "计划", "方案", "行程", "拼图", "生成", "做一个", "排一个",
            "瀹夋帓", "瑙勫垝", "璁″垝", "鏂规", "琛岀▼", "鎷煎浘", "鐢熸垚",
            "plan", "itinerary", "schedule");
    private static final List<String> EXPLORATION_KEYWORDS = List.of(
            "推荐", "看看", "有什么", "什么", "比较好", "适合", "去哪", "哪里", "附近",
            "灵感", "项目", "吃的", "玩什么",
            "鎺ㄨ崘", "鐪嬬湅", "鏈変粈涔", "姣旇緝濂", "閫傚悎", "闄勮繎",
            "recommend", "suggest", "ideas", "what");
    private static final List<String> MOVIE_KEYWORDS = List.of("电影", "影院", "场次", "鐢靛奖", "褰遍櫌", "movie", "cinema");
    private static final List<String> FOOD_KEYWORDS = List.of("吃的", "餐厅", "饭", "喝", "咖啡", "甜品", "椁愬巺", "restaurant", "food");
    private static final List<String> REASONING_KEYWORDS = List.of(
            "怪怪", "优化", "为什么", "解释", "别太尴尬", "氛围", "用心",
            "optimize", "why", "awkward", "vibe");
    private static final List<String> TIME_KEYWORDS = List.of(
            "上午", "中午", "下午", "晚上", "今晚", "明天", "周末", "周六", "周日", "星期六", "星期日",
            "涓婂崍", "涓崍", "涓嬪崍", "鏅氫笂", "浠婃櫄", "鏄庡ぉ", "鍛ㄦ湯",
            "today", "tomorrow");
    private static final List<String> COMPANION_KEYWORDS = List.of(
            "一个人", "两个人", "三个人", "情侣", "约会", "朋友", "孩子", "小孩", "娃", "家人", "一家", "亲子",
            "涓€涓汉", "涓や釜浜", "涓変釜浜", "鎯呬荆", "绾︿細", "鏈嬪弸", "瀛╁瓙",
            "family", "date");
    private static final List<String> ACTIVITY_KEYWORDS = List.of(
            "玩", "逛", "吃", "散步", "走走", "本地玩", "出门", "活动", "放电", "聚会", "好吃",
            "鍚冪殑", "鐜╀粈涔");
    private static final List<String> PREFERENCE_KEYWORDS = List.of(
            "别太远", "不要太远", "近一点", "本地", "好吃", "好走", "少走路", "预算", "便宜", "下雨", "室内", "轻松", "别折腾",
            "low budget", "nearby", "indoor", "easy");

    public InitialRouteCommand route(String prompt) {
        IntentEvidence evidence = evidence(prompt);
        if (evidence.hasReasoningRequest() && evidence.hasExplicitPlanRequest()) {
            return new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 0.82,
                    null, evidence, null);
        }
        if (evidence.hasMovieRequest()) {
            return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.94,
                    "MOVIE", evidence, null);
        }
        if (evidence.hasExplicitPlanRequest() && evidence.timeSignal() && evidence.headcountSignal()) {
            return new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 0.96,
                    null, evidence, null);
        }
        if (evidence.hasNearbyFoodRequest() && evidence.hasExplorationRequest()
                && !isCompleteStructuredPlanRequest(prompt, evidence)) {
            return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.9,
                    "DINING", evidence, null);
        }
        if (isCompleteStructuredPlanRequest(prompt, evidence)) {
            return new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 0.96,
                    null, evidence, null);
        }
        if (evidence.hasExplorationRequest() && !isExplicitBuildRequest(prompt)) {
            return new InitialRouteCommand(InitialRouteMode.CONSULT_CHAT, 0.88,
                    "IDEA", evidence, null);
        }
        if (evidence.hasExplicitPlanRequest()) {
            return new InitialRouteCommand(InitialRouteMode.ASK_CLARIFICATION, 0.84,
                    null, evidence, "你想安排在什么时间段、几个人一起去？补充这两个信息后，我再帮你生成拼图方案。");
        }
        return new InitialRouteCommand(InitialRouteMode.CONSULT_CHAT, 0.72,
                "IDEA", evidence, null);
    }

    public IntentEvidence evidence(String prompt) {
        String text = normalize(prompt);
        Optional<String> afterTime = parseTime(text);
        boolean hasTime = afterTime.isPresent()
                || RANGE.matcher(text).find()
                || DURATION.matcher(text).find()
                || containsAny(text, TIME_KEYWORDS);
        boolean hasHeadcount = HEADCOUNT.matcher(text).find()
                || containsAny(text, COMPANION_KEYWORDS);
        boolean hasPlan = containsAny(text, PLAN_KEYWORDS);
        boolean hasExplore = containsAny(text, EXPLORATION_KEYWORDS) || containsAny(text, ACTIVITY_KEYWORDS);
        boolean hasMovie = containsAny(text, MOVIE_KEYWORDS);
        boolean hasFood = containsAny(text, FOOD_KEYWORDS);
        boolean hasReasoning = containsAny(text, REASONING_KEYWORDS);
        return new IntentEvidence(hasTime, hasHeadcount, hasPlan, hasExplore, hasMovie,
                hasFood, hasReasoning, afterTime.orElse(null));
    }

    public boolean isCompleteStructuredPlanRequest(String prompt) {
        return isCompleteStructuredPlanRequest(prompt, evidence(prompt));
    }

    private boolean isCompleteStructuredPlanRequest(String prompt, IntentEvidence evidence) {
        String text = normalize(prompt);
        boolean hasActivity = evidence.hasExplorationRequest() || containsAny(text, ACTIVITY_KEYWORDS);
        boolean hasPreference = containsAny(text, PREFERENCE_KEYWORDS);
        boolean asksOpenEndedIdea = containsAny(text, List.of("什么比较好", "去哪比较好", "有什么推荐", "第一次约会", "第一次见面"));
        return evidence.timeSignal()
                && evidence.headcountSignal()
                && hasActivity
                && hasPreference
                && !evidence.hasMovieRequest()
                && !asksOpenEndedIdea;
    }

    private boolean isExplicitBuildRequest(String prompt) {
        String text = normalize(prompt);
        return containsAny(text, List.of(
                "生成方案", "生成行程", "完整方案", "完整行程", "规划到", "一键合成",
                "鐢熸垚鏂规", "鐢熸垚琛岀▼", "瀹屾暣鏂规", "瀹屾暣琛岀▼",
                "build plan"));
    }

    private Optional<String> parseTime(String text) {
        Matcher hhmm = HHMM.matcher(text);
        if (hhmm.find()) {
            int hour = Integer.parseInt(hhmm.group(1));
            if (isEvening(text) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:%s", hour, hhmm.group(2)));
        }
        Matcher range = RANGE.matcher(text);
        if (range.find()) {
            int hour = Integer.parseInt(range.group(1));
            if ((text.contains("下午") || text.contains("涓嬪崍") || isEvening(text)) && hour < 12) hour += 12;
            String minute = range.group(2) == null || range.group(2).isBlank() ? "00" : range.group(2);
            return Optional.of(String.format(Locale.ROOT, "%02d:%s", hour, minute));
        }
        Matcher hourOnly = HOUR_ONLY.matcher(text);
        if (hourOnly.find()) {
            int hour = Integer.parseInt(hourOnly.group(1));
            if ((text.contains("下午") || text.contains("涓嬪崍") || isEvening(text)) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:00", hour));
        }
        if (text.contains("两点") || text.contains("二点") || text.contains("涓ょ偣") || text.contains("浜岀偣")) {
            return Optional.of(text.contains("上午") || text.contains("涓婂崍") ? "02:00" : "14:00");
        }
        if (text.contains("三点") || text.contains("涓夌偣")) return Optional.of(text.contains("上午") || text.contains("涓婂崍") ? "03:00" : "15:00");
        if (text.contains("四点") || text.contains("鍥涚偣")) return Optional.of(text.contains("上午") || text.contains("涓婂崍") ? "04:00" : "16:00");
        if (text.contains("十点") || text.contains("鍗佺偣")) return Optional.of(isEvening(text) ? "22:00" : "10:00");
        return Optional.empty();
    }

    private boolean isEvening(String text) {
        return text.contains("晚上") || text.contains("今晚") || text.contains("夜")
                || text.contains("鏅氫笂") || text.contains("浠婃櫄")
                || text.contains("evening") || text.contains("tonight");
    }

    private boolean containsAny(String text, List<String> keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String normalize(String text) {
        return text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }
}
