package com.weekendplanner.engine;

import java.time.Instant;

public record RecentEvent(
        RecentEventType type,
        String summary,
        Instant createdAt
) {
    public RecentEvent {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
