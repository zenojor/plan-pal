package com.weekendplanner.dto;

import java.util.List;

public record Conflict(
        String conflictType,
        String severity,
        List<String> affectedSegments,
        String reason,
        List<RepairOption> repairOptions
) {
    public Conflict {
        conflictType = conflictType == null || conflictType.isBlank() ? "UNKNOWN" : conflictType;
        severity = severity == null || severity.isBlank() ? "MEDIUM" : severity;
        affectedSegments = affectedSegments == null ? List.of() : List.copyOf(affectedSegments);
        repairOptions = repairOptions == null ? List.of() : List.copyOf(repairOptions);
    }
}
