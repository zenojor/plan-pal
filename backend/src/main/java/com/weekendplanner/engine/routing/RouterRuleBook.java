package com.weekendplanner.engine.routing;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class RouterRuleBook {

    private final List<String> replacementKeywords = List.of(
            "换一批", "换个", "换一个", "不要这个", "不要了", "太远", "近一点", "附近", "再推荐", "重新推荐",
            "too far", "nearer", "replace", "another");
    private final List<String> cancelKeywords = List.of("取消", "算了", "cancel");
    private final List<String> editTimeKeywords = List.of("延长", "延到", "到晚上", "到夜里", "玩到", "结束到", "until");
    private final List<String> reasoningKeywords = List.of(
            "为什么", "解释", "怪怪", "优化", "氛围", "用心", "别太尴尬", "轻松一点", "不想太赶", "预算不高",
            "why", "explain", "optimize", "awkward", "vibe");
    private final List<String> eveningKeywords = List.of("晚上", "夜里", "今晚", "晚间", "evening", "tonight");

    public Optional<Integer> selectedIndex(String input) {
        String normalized = normalize(input);
        if (containsAny(normalized, List.of("选这个", "就这个", "这个吧", "选这", "加入这个", "加这个", "加进去"))) {
            return Optional.of(1);
        }
        if (containsAny(normalized, List.of("第一个", "第1个", "一号", "1号", "选一", "选第一个", "第一个吧", "first"))) {
            return Optional.of(1);
        }
        if (containsAny(normalized, List.of("第二个", "第2个", "二号", "2号", "选二", "选第二个", "第二个吧", "second"))) {
            return Optional.of(2);
        }
        if (containsAny(normalized, List.of("第三个", "第3个", "三号", "3号", "选三", "选第三个", "第三个吧", "third"))) {
            return Optional.of(3);
        }
        Matcher matcher = Pattern.compile("(^|\\D)([123])(\\D|$)").matcher(normalized);
        if (matcher.find()) return Optional.of(Integer.parseInt(matcher.group(2)));
        return Optional.empty();
    }

    public boolean isReplacementRequest(String input) {
        String normalized = normalize(input);
        if (containsAny(normalized, List.of("换一个", "换个", "换一批", "不要这个", "不要了",
                "太远", "近一点", "附近", "再推荐", "重新推荐"))) {
            return true;
        }
        return containsAny(normalized, replacementKeywords)
                || containsAny(normalized, List.of("换一批", "换个", "换一个", "不要这个", "太远", "近一点", "重新推荐"));
    }

    public boolean isCancelRequest(String input) {
        String normalized = normalize(input);
        return containsAny(normalized, List.of("取消", "算了", "先不用")) || containsAny(normalized, cancelKeywords);
    }

    public boolean isEditEndTimeRequest(String input) {
        String normalized = normalize(input);
        return parseEndTime(input).isPresent()
                && (containsAny(normalized, List.of("延长", "延到", "到晚上", "到夜里", "玩到", "结束到"))
                || containsAny(normalized, editTimeKeywords));
    }

    public boolean isReasoningRequest(String input) {
        String normalized = normalize(input);
        return containsAny(normalized, List.of("为什么", "解释", "奇怪", "优化", "氛围", "用心", "别太尴尬", "轻松一点", "不想太赶", "预算不高"))
                || containsAny(normalized, reasoningKeywords);
    }

    public Optional<String> parseEndTime(String input) {
        String normalized = normalize(input);
        if (containsAny(normalized, List.of("晚上十点", "晚十点", "夜里十点"))) return Optional.of("22:00");
        if (containsAny(normalized, List.of("晚上九点", "晚九点", "夜里九点"))) return Optional.of("21:00");
        if (containsAny(normalized, List.of("晚上八点", "晚八点", "夜里八点"))) return Optional.of("20:00");
        Matcher hhmm = Pattern.compile("(\\d{1,2})[:：](\\d{2})").matcher(normalized);
        if (hhmm.find()) {
            int hour = Integer.parseInt(hhmm.group(1));
            if (containsAny(normalized, eveningKeywords) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:%s", hour, hhmm.group(2)));
        }
        Matcher hourOnly = Pattern.compile("(\\d{1,2})\\s*(点|时|o'clock)").matcher(normalized);
        if (hourOnly.find()) {
            int hour = Integer.parseInt(hourOnly.group(1));
            if (containsAny(normalized, eveningKeywords) && hour < 12) hour += 12;
            return Optional.of(String.format(Locale.ROOT, "%02d:00", hour));
        }
        return Optional.empty();
    }

    public Map<String, Object> replacementSlots(String input) {
        String normalized = normalize(input);
        if (containsAny(normalized, List.of("近一点", "太远", "附近", "nearer", "too far"))) {
            return Map.of("distancePreference", "nearer", "excludePrevious", true);
        }
        if (containsAny(normalized, List.of("太贵", "便宜", "省钱", "cheap"))) {
            return Map.of("budgetLevel", "LOW", "excludePrevious", true);
        }
        return Map.of("excludePrevious", true);
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
