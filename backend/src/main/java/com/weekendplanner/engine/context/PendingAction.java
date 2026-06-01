package com.weekendplanner.engine.context;


import java.util.List;

public record PendingAction(
        String type,
        String candidateSetId,
        String targetSegmentId,
        List<String> expectedReplies
) {
    public PendingAction {
        expectedReplies = expectedReplies == null ? List.of() : List.copyOf(expectedReplies);
    }
}
