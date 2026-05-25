package com.weekendplanner.dto;

import java.util.List;

public record SegmentRequirement(
        String segmentId,
        String phase,
        String timeRange,
        Integer durationMinutes,
        List<String> keep,
        List<String> avoid,
        List<String> prefer,
        boolean locked
) {
    public SegmentRequirement {
        keep = keep == null ? List.of() : List.copyOf(keep);
        avoid = avoid == null ? List.of() : List.copyOf(avoid);
        prefer = prefer == null ? List.of() : List.copyOf(prefer);
    }
}
