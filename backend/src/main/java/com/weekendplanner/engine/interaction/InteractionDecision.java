package com.weekendplanner.engine.interaction;

import com.weekendplanner.dto.PlanPatch;

public record InteractionDecision(
        InteractionCommand command,
        double confidence,
        String reason,
        String targetSegmentId,
        String candidateSetId,
        Integer selectedIndex,
        PlanPatch directPatch,
        String clarificationQuestion
) {
    public InteractionDecision {
        command = command == null ? InteractionCommand.MODIFY_PLAN : command;
    }

    public static InteractionDecision of(InteractionCommand command, double confidence, String reason) {
        return new InteractionDecision(command, confidence, reason, null, null, null, null, null);
    }
}
