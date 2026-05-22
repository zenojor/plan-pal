package com.weekendplanner.dto;

/**
 * 方案中的单个时间片步骤
 */
public record PlanStep(
        String timeRange,     // "14:00-16:00"
        String phase,         // ACTIVITY / TRANSIT / DINING / EVENING
        String poiName,       // POI 名称
        String poiId,         // POI ID
        String action,        // 动作描述
        String bookingStatus, // 预订状态
        String note           // 备注
) {}
