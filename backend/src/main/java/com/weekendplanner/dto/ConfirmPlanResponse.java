package com.weekendplanner.dto;

import java.util.List;

public record ConfirmPlanResponse(
        String orderGroupId,
        String status,
        List<String> executedOrders,
        List<String> failedOrders,
        String notificationText,
        List<PlanStep> timeline,
        int version,
        PlanStatus planStatus
) {
    public ConfirmPlanResponse(String orderGroupId,
                               String status,
                               List<String> executedOrders,
                               List<String> failedOrders,
                               String notificationText,
                               List<PlanStep> timeline) {
        this(orderGroupId, status, executedOrders, failedOrders, notificationText, timeline, 1,
                PlanStatus.CONFIRMED);
    }
}
