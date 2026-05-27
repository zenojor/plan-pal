package com.weekendplanner.engine;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class InitialRequestRouter {

    private static final Pattern HHMM = Pattern.compile("(\\d{1,2})\\s*[:：]\\s*(\\d{2})");
    private static final Pattern HOUR_ONLY = Pattern.compile("(\\d{1,2})\\s*(点|時|时|o'clock)");
    private static final Pattern HEADCOUNT = Pattern.compile("(\\d+)\\s*(个人|人|位|persons?|people)");

    private static final List<String> PLAN_KEYWORDS = List.of(
            "安排", "规划", "计划", "方案", "行程", "拼图", "生成", "做一个", "排一下",
            "plan", "itinerary", "schedule");
    private static final List<String> EXPLORATION_KEYWORDS = List.of(
            "推荐", "看看", "有什么", "什么", "比较好", "适合", "去哪", "哪里", "附近",
            "灵感", "项目", "吃的", "玩什么", "recommend", "suggest", "ideas", "what");
    private static final List<String> MOVIE_KEYWORDS = List.of("电影", "影院", "场次", "movie", "cinema");
    private static final List<String> FOOD_KEYWORDS = List.of("吃的", "餐厅", "饭", "喝", "咖啡", "甜品", "restaurant", "food");
    private static final List<String> REASONING_KEYWORDS = List.of(
            "怪怪", "优化", "为什么", "解释", "别太尴尬", "氛围", "用心",
            "optimize", "why", "awkward", "vibe");

    public InitialRouteCommand route(String prompt) {
        IntentEvidence evidence = evidence(prompt);
        if (evidence.hasReasoningRequest() && evidence.hasExplicitPlanRequest()) {
            return new InitialRouteCommand(InitialRouteMode.AGENT_REASONING, 0.82,
                    null, evidence, null);
        }
        if (evidence.hasMovieRequest()) {
            return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.94,
                    "MOVIE", evidence, null);
        }
        if (evidence.hasNearbyFoodRequest() && evidence.hasExplorationRequest()) {
            return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.9,
                    "DINING", evidence, null);
        }
        if (evidence.hasExplorationRequest() && !isExplicitBuildRequest(prompt)) {
            return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.88,
                    "IDEA", evidence, null);
        }
        if (evidence.hasExplicitPlanRequest() && evidence.hasExplicitTime() && evidence.hasExplicitHeadcount()) {
            return new InitialRouteCommand(InitialRouteMode.CREATE_PLAN, 0.96,
                    null, evidence, null);
        }
        if (evidence.hasExplicitPlanRequest()) {
            return new InitialRouteCommand(InitialRouteMode.ASK_CLARIFICATION, 0.84,
                    null, evidence, "Please add a time range and headcount, then I can build the puzzle plan.");
        }
        return new InitialRouteCommand(InitialRouteMode.RESEARCH_AND_RENDER, 0.72,
                "IDEA", evidence, null);
    }

    public IntentEvidence evidence(String prompt) {
        String text = normalize(prompt);
        Optional<String> afterTime = parseTime(text);
        boolean hasTime = afterTime.isPresent()
                || containsAny(text, List.of("上午", "中午", "下午", "晚上", "今晚", "明天", "周末", "today", "tomorrow"));
        boolean hasHeadcount = HEADCOUNT.matcher(text).find()
                || containsAny(text, List.of("一个人", "两个人", "三个人", "情侣", "约会", "朋友", "孩子", "family", "date"));
        boolean hasPlan = containsAny(text, PLAN_KEYWORDS);
        boolean hasExplore = containsAny(text, EXPLORATION_KEYWORDS);
        boolean hasMovie = containsAny(text, MOVIE_KEYWORDS);
        boolean hasFood = containsAny(text, FOOD_KEYWORDS);
        boolean hasReasoning = containsAny(text, REASONING_KEYWORDS);
        return new IntentEvidence(hasTime, hasHeadcount, hasPlan, hasExplore, hasMovie,
                hasFood, hasReasoning, afterTime.orElse(null));
    }

    private boolean isExplicitBuildRequest(String prompt) {
        String text = normalize(prompt);
        return containsAny(text, List.of("生成方案", "生成行程", "完整方案", "完整行程", "规划到", "一键合成", "build plan"));
    }

    private Optional<String> parseTime(String text) {
        Matcher hhmm = HHMM.matcher(text);
        if (hhmm.find()) {
            int hour = Integer.parseInt(hhmm.group(1));
            if (isEvening(text) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:%s", hour, hhmm.group(2)));
        }
        Matcher hourOnly = HOUR_ONLY.matcher(text);
        if (hourOnly.find()) {
            int hour = Integer.parseInt(hourOnly.group(1));
            if ((text.contains("下午") || isEvening(text)) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:00", hour));
        }
        if (text.contains("两点") || text.contains("二点")) return Optional.of(text.contains("上午") ? "02:00" : "14:00");
        if (text.contains("三点")) return Optional.of(text.contains("上午") ? "03:00" : "15:00");
        if (text.contains("四点")) return Optional.of(text.contains("上午") ? "04:00" : "16:00");
        if (text.contains("十点")) return Optional.of(isEvening(text) ? "22:00" : "10:00");
        return Optional.empty();
    }

    private boolean isEvening(String text) {
        return text.contains("晚上") || text.contains("今晚") || text.contains("夜") || text.contains("evening") || text.contains("tonight");
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
