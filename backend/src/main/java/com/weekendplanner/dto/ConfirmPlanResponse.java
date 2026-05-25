package com.weekendplanner.dto;

import java.util.List;

/**
 * 确认执行后的订单结果。
 */
public record ConfirmPlanResponse(
        String orderGroupId,
        String status,
        List<String> executedOrders,
        List<String> failedOrders,
        String notificationText,
        List<PlanStep> timeline
) {}
