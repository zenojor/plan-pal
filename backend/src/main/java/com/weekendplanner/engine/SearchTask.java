package com.weekendplanner.engine;

import java.util.List;

public record SearchTask(
        String id,
        String phase,
        String category,
        List<String> tags,
        int radiusKm,
        int limit,
        int priority,
        String reason
) {
    public SearchTask {
        tags = tags == null ? List.of() : List.copyOf(tags);
    }
}
