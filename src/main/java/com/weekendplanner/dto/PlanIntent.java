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
        String originalPrompt
) {}
