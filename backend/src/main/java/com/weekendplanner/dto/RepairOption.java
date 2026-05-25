package com.weekendplanner.dto;

import java.util.List;

public record RepairOption(
        String optionId,
        String label,
        String description,
        String action,
        String targetSegmentId,
        PlanDelta planDelta,
        List<String> affectedPoiIds,
        PoiPreview preview
) {
    public RepairOption {
        affectedPoiIds = affectedPoiIds == null ? List.of() : List.copyOf(affectedPoiIds);
    }
}
