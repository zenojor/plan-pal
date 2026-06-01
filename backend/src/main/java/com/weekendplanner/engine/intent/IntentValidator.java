package com.weekendplanner.engine.intent;


import com.weekendplanner.dto.PlanIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Local validator and repair layer for PlanIntent.
 * Ensures the extracted intent from LLM or rules is structurally correct and complete.
 */
@Component
public class IntentValidator {

    private static final Set<String> ALLOWED_SCENES = Set.of("SOLO", "FAMILY", "SOCIAL", "DATE");
    private static final Set<String> ALLOWED_PACE = Set.of("RELAXED", "NORMAL", "COMPACT");
    private static final Set<String> ALLOWED_BUDGET = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> ALLOWED_TRANSPORT = Set.of("WALK", "DRIVE", "PUBLIC_TRANSIT");
    private static final Set<String> ALLOWED_SEGMENTS = Set.of("ACTIVITY", "DINING", "DRINKS", "LEISURE");

    public record MissingFields(boolean missingTime, boolean missingHeadcount) {}

    /**
     * Validates and repairs the PlanIntent.
     */
    public PlanIntent validate(PlanIntent intent, String originalPrompt) {
        if (intent == null) {
            return createDefaultIntent(originalPrompt);
        }

        // 1. Validate headcount
        int headcount = intent.headcount();
        String lowerPrompt = originalPrompt == null ? "" : originalPrompt.toLowerCase(Locale.ROOT);
        boolean mentionsFamilySize = containsKeywords(lowerPrompt, "一家三口", "三口之家", "三口人", "三口", "一家四口", "四口之家", "四口人", "四口", "一家五口", "五口之家", "五口人", "涓夊彛", "鍥涘彛");
        boolean mentionsChild = containsKeywords(lowerPrompt, "孩子", "儿童", "娃", "亲子", "带娃", "瀛╁瓙", "鍎跨", "濞?", "浜插瓙", "甯﹀▋");
        boolean mentionsFriend = containsKeywords(lowerPrompt, "朋友", "同学", "同事", "战友", "闺蜜", "聚会", "鏈嬪弸", "鍚屽", "鍚屼簨", "鎴樺弸", "闂鸿湝", "鑱氫細");
        boolean mentionsPartner = containsKeywords(lowerPrompt, "伴侣", "情侣", "老婆", "老公", "妻子", "丈夫", "女朋友", "男朋友", "约会", "鎯呬荆", "鑰佸﹩", "鑰佸叕", "濡诲瓙", "涓堝か", "濂虫湅鍙?", "鐢锋湅鍙?", "绾︿細");
        boolean hasExplicitNumericHeadcount = hasExplicitNumericHeadcount(lowerPrompt);

        if (headcount <= 1) {
            if (containsKeywords(lowerPrompt, "一家三口", "三口之家", "三口人", "三口", "涓夊彛")) {
                headcount = 3;
            } else if (containsKeywords(lowerPrompt, "一家四口", "四口之家", "四口人", "四口", "鍥涘彛")) {
                headcount = 4;
            } else if (containsKeywords(lowerPrompt, "一家五口", "五口之家", "五口人")) {
                headcount = 5;
            } else if (mentionsPartner && mentionsChild && mentionsFriend) {
                headcount = 4;
            } else if (mentionsChild && mentionsFriend) {
                headcount = 3;
            } else if (mentionsPartner && mentionsChild) {
                headcount = 3;
            } else if (containsKeywords(lowerPrompt, "双人", "夫妻", "两口子", "俩人", "两人", "俩个", "约会", "情侣")) {
                headcount = 2;
            } else if (mentionsChild) {
                headcount = 2; // Parent + child
            } else if (headcount <= 0) {
                headcount = 1;
            }
        }
        if (!hasExplicitNumericHeadcount) {
            if (mentionsPartner && mentionsChild && mentionsFriend && headcount < 4) {
                headcount = 4;
            } else if ((mentionsChild && mentionsFriend || mentionsPartner && mentionsChild) && headcount < 3) {
                headcount = 3;
            }
        }

        // 2. Validate participants
        List<String> participants = intent.participants();
        if (participants == null) {
            participants = new ArrayList<>();
        } else {
            participants = new ArrayList<>(participants);
        }

        // 3. Validate times
        String start = intent.startTime();
        String end = intent.endTime();
        if (!isTimeFormat(start)) {
            start = "14:00";
        }
        if (!isTimeFormat(end)) {
            end = addMinutes(start, 240);
        }
        if (toMinutes(end) <= toMinutes(start)) {
            end = addMinutes(start, 240);
        }
        int totalMinutes = minutesBetween(start, end);

        // 4. Validate hasChildren and childAge
        boolean hasChildren = intent.hasChildren() || mentionsChild || mentionsFamilySize;
        for (String p : participants) {
            if (containsKeywords(p, "孩子", "儿童", "娃", "子")) {
                hasChildren = true;
                break;
            }
        }
        Integer childAge = intent.childAge();
        if (childAge != null && childAge < 0) {
            childAge = null;
        }

        // 5. Validate sceneType
        String sceneType = intent.sceneType();
        if (sceneType == null) {
            sceneType = "";
        }
        sceneType = sceneType.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_SCENES.contains(sceneType)) {
            // Infer sceneType
            if (hasChildren) {
                sceneType = "FAMILY";
            } else if (containsKeywords(originalPrompt, "约会", "情侣", "老婆", "老公", "女朋友", "男朋友", "伴侣")) {
                sceneType = "DATE";
            } else if (headcount == 1) {
                sceneType = "SOLO";
            } else {
                sceneType = "SOCIAL";
            }
        }
        if (hasChildren) {
            sceneType = "FAMILY";
        }

        // 6. Validate requestedSegments
        List<String> segments = intent.requestedSegments();
        List<String> validSegments = new ArrayList<>();
        if (segments != null) {
            for (String seg : segments) {
                if (seg != null) {
                    String normSeg = seg.trim().toUpperCase(Locale.ROOT);
                    if (ALLOWED_SEGMENTS.contains(normSeg)) {
                        validSegments.add(normSeg);
                    }
                }
            }
        }
        if (validSegments.isEmpty()) {
            if ("DATE".equalsIgnoreCase(sceneType)) {
                validSegments.addAll(List.of("LEISURE", "DINING"));
            } else if ("FAMILY".equalsIgnoreCase(sceneType)) {
                validSegments.addAll(List.of("ACTIVITY", "DINING"));
            } else {
                validSegments.addAll(List.of("ACTIVITY", "DINING"));
            }
        }

        // 7. Validate other enums
        String pace = enumOr(intent.pace(), ALLOWED_PACE, "NORMAL");
        String budgetLevel = enumOr(intent.budgetLevel(), ALLOWED_BUDGET, "MEDIUM");
        String transport = enumOr(intent.preferredTransportMode(), ALLOWED_TRANSPORT, "PUBLIC_TRANSIT");

        // 8. Validate isConsultingMode
        boolean isConsultingMode = intent.isConsultingMode();
        if (isConsultingMode) {
            // If explicit time and headcount are both provided, force consulting mode to false
            if (hasTimeKeywords(originalPrompt) && hasHeadcountKeywords(originalPrompt)) {
                isConsultingMode = false;
            }
        }

        return new PlanIntent(
                headcount,
                List.copyOf(participants),
                start,
                end,
                totalMinutes,
                sceneType,
                List.copyOf(validSegments),
                intent.dietaryConstraints() == null ? List.of() : List.copyOf(intent.dietaryConstraints()),
                intent.drinkPreference() == null ? "" : intent.drinkPreference(),
                intent.locationScope() == null ? "NEARBY" : intent.locationScope(),
                originalPrompt,
                pace,
                budgetLevel,
                hasChildren,
                childAge,
                transport,
                intent.avoid() == null ? List.of() : List.copyOf(intent.avoid()),
                intent.mustHave() == null ? List.of() : List.copyOf(intent.mustHave()),
                intent.weatherSensitive(),
                isConsultingMode
        );
    }

    /**
     * Check if the intent has missing critical info.
     */
    public boolean isMissingCriticalInfo(PlanIntent intent) {
        if (intent.isConsultingMode()) {
            return false;
        }
        return isMissingTime(intent) || isMissingHeadcount(intent);
    }

    /**
     * Detects missing fields.
     */
    public MissingFields detectMissingFields(PlanIntent intent) {
        return new MissingFields(isMissingTime(intent), isMissingHeadcount(intent));
    }

    public boolean isMissingTime(PlanIntent intent) {
        if (intent.startTime() == null || intent.startTime().isBlank()) {
            return true;
        }
        // If the start time is 14:00 (fallback default) and original prompt doesn't mention time
        if ("14:00".equals(intent.startTime()) && !hasTimeKeywords(intent.originalPrompt())) {
            return true;
        }
        return false;
    }

    public boolean isMissingHeadcount(PlanIntent intent) {
        if (intent.headcount() <= 0) {
            return true;
        }
        // If headcount is 1 (fallback default) and original prompt doesn't mention headcount/participant keywords
        if (intent.headcount() == 1 && !hasHeadcountKeywords(intent.originalPrompt())) {
            return true;
        }
        return false;
    }

    private boolean isTimeFormat(String time) {
        return time != null && time.matches("\\d{1,2}:\\d{2}");
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String addMinutes(String time, int minutes) {
        int total = toMinutes(time) + minutes;
        return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60);
    }

    private int minutesBetween(String start, String end) {
        return Math.max(60, toMinutes(end) - toMinutes(start));
    }

    private String enumOr(String value, Set<String> allowed, String defaultValue) {
        if (value == null) return defaultValue;
        String norm = value.trim().toUpperCase(Locale.ROOT);
        return allowed.contains(norm) ? norm : defaultValue;
    }

    private boolean hasTimeKeywords(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String lower = prompt.toLowerCase(Locale.ROOT);
        return lower.contains("点") || lower.contains("分") || lower.contains("时") 
                || lower.contains("am") || lower.contains("pm") || lower.contains("clock") 
                || lower.contains("：") || lower.contains(":") || lower.contains("下午") 
                || lower.contains("晚上") || lower.contains("中午") || lower.contains("上午") 
                || lower.contains("早上") || lower.contains("夜里") || lower.contains("凌晨");
    }

    private boolean hasHeadcountKeywords(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        String lower = prompt.toLowerCase(Locale.ROOT);
        return lower.contains("人") || lower.contains("位") || lower.contains("独自") 
                || lower.contains("自己") || lower.contains("情侣") || lower.contains("老婆") 
                || lower.contains("老公") || lower.contains("妻子") || lower.contains("丈夫") 
                || lower.contains("孩子") || lower.contains("娃") || lower.contains("朋友") 
                || lower.contains("聚会") || lower.contains("聚聚") || lower.contains("战友")
                || lower.contains("闺蜜") || lower.contains("同学") || lower.contains("同事")
                || lower.contains("团建") || lower.contains("约会");
    }

    private boolean containsKeywords(String text, String... keywords) {
        if (text == null) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String kw : keywords) {
            if (lower.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasExplicitNumericHeadcount(String prompt) {
        if (prompt == null || prompt.isBlank()) return false;
        return Pattern.compile("\\d+\\s*(个?人|位|浜|浣)").matcher(prompt).find()
                || containsKeywords(prompt, "一个人", "1个人", "1 人", "两人", "双人", "三人", "四人", "五人", "一人", "二人", "2人", "3人", "4人", "5人");
    }

    private PlanIntent createDefaultIntent(String prompt) {
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
}
