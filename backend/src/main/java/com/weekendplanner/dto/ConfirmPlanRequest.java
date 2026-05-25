package com.weekendplanner.dto;

import java.util.List;

public record ConfirmPlanRequest(
        String planId,
        String userId,
        List<PlanStep> timeline,
        int headcount,
        String notificationText,
        int version,
        String idempotencyKey
) {
    public ConfirmPlanRequest(String planId,
                              String userId,
                              List<PlanStep> timeline,
                              int headcount,
                              String notificationText) {
        this(planId, userId, timeline, headcount, notificationText, 0, null);
    }
}
