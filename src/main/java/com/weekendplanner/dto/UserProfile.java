package com.weekendplanner.dto;

/**
 * 用户画像 - 从自然语言中提取的约束
 */
public record UserProfile(
        int headcount,             // 总人数
        int childCount,            // 儿童数量
        boolean hasDietConstraint, // 是否有饮食限制
        String dietaryType,        // light/healthy / normal
        boolean isSocialScene,     // 是否社交场景
        String startTime,          // 出发时间 "14:00"
        int preferredHours,        // 偏好时长(小时)
        int maxRadiusKm,           // 最大距离(km)
        String originalPrompt      // 原始输入
) {}
