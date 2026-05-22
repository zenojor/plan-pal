package com.weekendplanner.dto;

/**
 * 排队/可用性查验响应
 */
public record CheckResponse(
        String poiId,
        String status,           // AVAILABLE / QUEUED / SOLD_OUT
        int queueTimeMinutes,    // 排队分钟数(0表示无需排队)
        boolean needPreOrder     // 是否需要提前预订
) {}
