package com.weekendplanner.engine.candidate;

import java.time.Instant;
import java.util.List;

public record CandidateSet(
        String candidateSetId,
        String type,
        String targetSegmentId,
        List<CandidateItem> items,
        Instant createdAt
) {
    public CandidateSet {
        items = items == null ? List.of() : List.copyOf(items);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
