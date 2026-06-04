package com.weekendplanner.engine.context;

import com.weekendplanner.dto.PlanStep;

import java.util.List;

public record ConfirmContext(
        List<PlanStep> submittedTimeline,
        int version,
        String idempotencyKey,
        String userId,
        String planId
) {
    public ConfirmContext {
        submittedTimeline = submittedTimeline == null ? List.of() : List.copyOf(submittedTimeline);
    }
}
