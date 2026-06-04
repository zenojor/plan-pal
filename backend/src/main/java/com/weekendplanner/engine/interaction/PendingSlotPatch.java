package com.weekendplanner.engine.interaction;

import com.weekendplanner.engine.understanding.TurnUnderstanding;

import java.util.Map;

public record PendingSlotPatch(
        Map<String, Object> slots,
        boolean question,
        boolean correction,
        String reason,
        TurnUnderstanding understanding
) {
    public PendingSlotPatch(Map<String, Object> slots,
                            boolean question,
                            boolean correction,
                            String reason) {
        this(slots, question, correction, reason, null);
    }

    public PendingSlotPatch {
        slots = slots == null ? Map.of() : Map.copyOf(slots);
        reason = reason == null ? "" : reason;
    }

    public boolean hasSlots() {
        return !slots.isEmpty();
    }

    public boolean shouldContinueWorkflow() {
        return hasSlots() || correction;
    }

    public static PendingSlotPatch empty() {
        return new PendingSlotPatch(Map.of(), false, false, "", null);
    }
}
