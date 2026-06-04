package com.weekendplanner.engine.context;

import com.weekendplanner.dto.PlanPatch;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

public record PendingAction(
        String type,
        String candidateSetId,
        String targetSegmentId,
        List<String> expectedReplies,
        String workflowType,
        PlanPatch selectedPatch,
        String selectedLabel,
        List<String> requiredSlots,
        Map<String, Object> collectedSlots,
        boolean preserveAfterQa
) {
    public PendingAction(String type, String candidateSetId, String targetSegmentId, List<String> expectedReplies) {
        this(type, candidateSetId, targetSegmentId, expectedReplies, null, null, null,
                List.of(), Map.of(), true);
    }

    public PendingAction {
        expectedReplies = expectedReplies == null ? List.of() : List.copyOf(expectedReplies);
        requiredSlots = requiredSlots == null ? List.of() : List.copyOf(requiredSlots);
        collectedSlots = collectedSlots == null ? Map.of() : Map.copyOf(collectedSlots);
    }

    public PendingAction withType(String nextType) {
        return new PendingAction(nextType, candidateSetId, targetSegmentId, expectedReplies, workflowType,
                selectedPatch, selectedLabel, requiredSlots, collectedSlots, preserveAfterQa);
    }

    public PendingAction withSelectedPatch(PlanPatch patch, String label, String nextWorkflowType, List<String> slots) {
        return new PendingAction(type, candidateSetId, targetSegmentId, expectedReplies, nextWorkflowType,
                patch, label, slots, collectedSlots, preserveAfterQa);
    }

    public PendingAction mergeCollectedSlots(Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) return this;
        Map<String, Object> merged = new LinkedHashMap<>(collectedSlots);
        merged.putAll(slots);
        return new PendingAction(type, candidateSetId, targetSegmentId, expectedReplies, workflowType,
                selectedPatch, selectedLabel, requiredSlots, merged, preserveAfterQa);
    }
}
