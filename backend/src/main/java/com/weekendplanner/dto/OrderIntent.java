package com.weekendplanner.dto;

/**
 * 规划阶段生成的待执行动作，确认方案后才会转成真实 mock 订单。
 */
public record OrderIntent(
        String orderIntentId,
        String type,
        String poiId,
        String poiName,
        int headcount,
        String targetTime,
        String status
) {}
