package com.weekendplanner.dto;

import java.util.List;

public record PlanDelta(
        String operation,
        String scope,
        PlanPatch patch,
        ConstraintSet changedConstraints,
        List<String> lockedSegmentIds,
        List<SegmentRequirement> segmentRequirements,
        String replanScope,
        boolean requiresSearch
) {
    public PlanDelta {
        operation = operation == null || operation.isBlank() ? "REPAIR" : operation;
        scope = scope == null || scope.isBlank() ? "PLAN" : scope;
        lockedSegmentIds = lockedSegmentIds == null ? List.of() : List.copyOf(lockedSegmentIds);
        segmentRequirements = segmentRequirements == null ? List.of() : List.copyOf(segmentRequirements);
        replanScope = replanScope == null || replanScope.isBlank() ? "AFFECTED_SEGMENTS" : replanScope;
    }

    public static PlanDelta fromPatch(PlanPatch patch) {
        PlanPatch safePatch = patch == null
                ? new PlanPatch("MODIFY_PLAN", "KEEP_AND_REPLAN", null, null, false)
                : patch;
        return new PlanDelta(
                safePatch.editType(),
                "PLAN",
                safePatch,
                null,
                safePatch.requirements().keep(),
                List.of(new SegmentRequirement(
                        safePatch.target().segmentId(),
                        firstNonBlank(safePatch.target().phase(), safePatch.target().activityType()),
                        safePatch.target().timeRange(),
                        null,
                        safePatch.requirements().keep(),
                        safePatch.requirements().avoid(),
                        safePatch.requirements().prefer(),
                        false)),
                safePatch.target().segmentId() == null ? "PLAN" : "AFFECTED_SEGMENTS",
                safePatch.requiresSearch());
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        return second;
    }
}
