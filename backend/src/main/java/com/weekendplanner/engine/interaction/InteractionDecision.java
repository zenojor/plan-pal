package com.weekendplanner.engine.interaction;

import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.engine.understanding.TurnUnderstanding;

public record InteractionDecision(
        InteractionCommand command,
        double confidence,
        String reason,
        String targetSegmentId,
        String candidateSetId,
        Integer selectedIndex,
        PlanPatch directPatch,
        String clarificationQuestion,
        PendingSlotPatch pendingSlotPatch,
        TurnUnderstanding understanding
) {
    public InteractionDecision(InteractionCommand command,
                               double confidence,
                               String reason,
                               String targetSegmentId,
                               String candidateSetId,
                               Integer selectedIndex,
                               PlanPatch directPatch,
                               String clarificationQuestion) {
        this(command, confidence, reason, targetSegmentId, candidateSetId, selectedIndex,
                directPatch, clarificationQuestion, null, null);
    }

    public InteractionDecision {
        command = command == null ? InteractionCommand.MODIFY_PLAN : command;
    }

    public static InteractionDecision of(InteractionCommand command, double confidence, String reason) {
        return new InteractionDecision(command, confidence, reason, null, null, null, null, null, null, null);
    }

    public static InteractionDecision of(InteractionCommand command,
                                         double confidence,
                                         String reason,
                                         TurnUnderstanding understanding) {
        return new InteractionDecision(command, confidence, reason, null, null, null, null, null, null,
                understanding);
    }

    public static InteractionDecision of(InteractionCommand command,
                                         double confidence,
                                         String reason,
                                         PendingSlotPatch pendingSlotPatch) {
        return new InteractionDecision(command, confidence, reason, null, null, null, null, null, pendingSlotPatch,
                pendingSlotPatch == null ? null : pendingSlotPatch.understanding());
    }
}
