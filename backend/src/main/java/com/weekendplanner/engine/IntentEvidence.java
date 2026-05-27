package com.weekendplanner.engine;

public record IntentEvidence(
        boolean hasExplicitTime,
        boolean hasExplicitHeadcount,
        boolean hasExplicitPlanRequest,
        boolean hasExplorationRequest,
        boolean hasMovieRequest,
        boolean hasNearbyFoodRequest,
        boolean hasReasoningRequest,
        String afterTime
) {
}
