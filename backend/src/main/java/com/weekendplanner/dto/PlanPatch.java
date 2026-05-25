package com.weekendplanner.dto;

import java.util.List;

public record PlanPatch(
        String intent,
        String editType,
        Target target,
        Requirements requirements,
        boolean requiresSearch
) {
    public PlanPatch {
        intent = intent == null || intent.isBlank() ? "MODIFY_PLAN" : intent;
        editType = editType == null || editType.isBlank() ? "KEEP_AND_REPLAN" : editType;
        target = target == null ? new Target(null, null, null, null, null, null) : target;
        requirements = requirements == null ? new Requirements(null, null, null, null, null, null, false) : requirements;
    }

    public record Target(
            String segmentId,
            String timeRange,
            String activityType,
            String phase,
            String anchorSegmentId,
            String position
    ) {
        public Target(String segmentId, String timeRange, String activityType, String phase) {
            this(segmentId, timeRange, activityType, phase, null, null);
        }
    }

    public record Requirements(
            List<String> keep,
            List<String> avoid,
            List<String> prefer,
            String pace,
            String budgetLevel,
            String preferredTransportMode,
            boolean endEarlier
    ) {
        public Requirements {
            keep = keep == null ? List.of() : List.copyOf(keep);
            avoid = avoid == null ? List.of() : List.copyOf(avoid);
            prefer = prefer == null ? List.of() : List.copyOf(prefer);
        }
    }
}
