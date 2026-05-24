package com.weekendplanner.dto;

import java.util.List;

/**
 * 从自然语言中抽取出的规划意图。
 */
public record PlanIntent(
        int headcount,
        List<String> participants,
        String startTime,
        String endTime,
        int totalMinutes,
        String sceneType,
        List<String> requestedSegments,
        List<String> dietaryConstraints,
        String drinkPreference,
        String locationScope,
        String originalPrompt,
        String pace,
        String budgetLevel,
        boolean hasChildren,
        Integer childAge,
        String preferredTransportMode,
        List<String> avoid,
        List<String> mustHave,
        boolean weatherSensitive,
        boolean isConsultingMode
) {
    public PlanIntent {
        pace = pace == null || pace.isBlank() ? "NORMAL" : pace;
        budgetLevel = budgetLevel == null || budgetLevel.isBlank() ? "MEDIUM" : budgetLevel;
        preferredTransportMode = preferredTransportMode == null || preferredTransportMode.isBlank()
                ? "PUBLIC_TRANSIT" : preferredTransportMode;
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        mustHave = mustHave == null ? List.of() : List.copyOf(mustHave);
    }

    public PlanIntent(
            int headcount,
            List<String> participants,
            String startTime,
            String endTime,
            int totalMinutes,
            String sceneType,
            List<String> requestedSegments,
            List<String> dietaryConstraints,
            String drinkPreference,
            String locationScope,
            String originalPrompt
    ) {
        this(headcount, participants, startTime, endTime, totalMinutes, sceneType, requestedSegments,
             dietaryConstraints, drinkPreference, locationScope, originalPrompt, false);
    }

    public PlanIntent(
            int headcount,
            List<String> participants,
            String startTime,
            String endTime,
            int totalMinutes,
            String sceneType,
            List<String> requestedSegments,
            List<String> dietaryConstraints,
            String drinkPreference,
            String locationScope,
            String originalPrompt,
            boolean isConsultingMode
    ) {
        this(headcount, participants, startTime, endTime, totalMinutes, sceneType, requestedSegments,
             dietaryConstraints, drinkPreference, locationScope, originalPrompt,
             "NORMAL", "MEDIUM", false, null, "PUBLIC_TRANSIT", List.of(), List.of(), false,
             isConsultingMode);
    }

    /**
     * 判断是否缺失拼图模式所需的关键规划因子（时间段或人数）。
     */
    public boolean isMissingCriticalPlanningInfo() {
        if (isConsultingMode) {
            return false; // 模糊探索模式不视作缺失
        }
        if (originalPrompt == null || originalPrompt.isBlank()) {
            return true;
        }
        String lower = originalPrompt.toLowerCase(java.util.Locale.ROOT);
        
        // 1. 时间显式包含判断
        boolean hasTime = true;
        if (startTime == null || startTime.isBlank()) {
            hasTime = false;
        } else if ("14:00".equals(startTime)) {
            hasTime = lower.contains("点") || lower.contains("分") || lower.contains("时") 
                    || lower.contains("am") || lower.contains("pm") || lower.contains("clock") 
                    || lower.contains("：") || lower.contains(":") || lower.contains("下午") 
                    || lower.contains("晚上") || lower.contains("中午") || lower.contains("上午") 
                    || lower.contains("早上") || lower.contains("夜里") || lower.contains("凌晨");
        }
                
        // 2. 人数/参与者显式包含判断
        boolean hasHeadcount = true;
        if (headcount <= 0) {
            hasHeadcount = false;
        } else if (headcount == 1) {
            hasHeadcount = lower.contains("人") || lower.contains("位") || lower.contains("独自") 
                    || lower.contains("自己") || lower.contains("情侣") || lower.contains("老婆") 
                    || lower.contains("老公") || lower.contains("妻子") || lower.contains("丈夫") 
                    || lower.contains("孩子") || lower.contains("娃") || lower.contains("朋友") 
                    || lower.contains("聚会") || lower.contains("聚聚") || lower.contains("战友")
                    || lower.contains("闺蜜") || lower.contains("同学") || lower.contains("同事")
                    || lower.contains("团建") || lower.contains("约会");
        }
                
        return !hasTime || !hasHeadcount;
    }
}
