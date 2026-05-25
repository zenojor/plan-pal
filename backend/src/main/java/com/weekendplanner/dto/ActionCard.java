package com.weekendplanner.dto;

import java.util.List;

public record ActionCard(
        String id,
        String title,
        String description,
        List<ActionOption> options,
        String inputPlaceholder,
        boolean allowCustomInput
) {
    public ActionCard {
        options = options == null ? List.of() : List.copyOf(options);
    }

    public record ActionOption(
            String id,
            String label,
            String description,
            String actionType,
            String targetSegmentId,
            String prompt,
            PlanPatch planPatch,
            List<String> poiIds,
            PoiPreview poiPreview
    ) {
        public ActionOption(String id,
                            String label,
                            String description,
                            String actionType,
                            String targetSegmentId,
                            String prompt,
                            PlanPatch planPatch,
                            List<String> poiIds) {
            this(id, label, description, actionType, targetSegmentId, prompt, planPatch, poiIds, null);
        }
    }
}
