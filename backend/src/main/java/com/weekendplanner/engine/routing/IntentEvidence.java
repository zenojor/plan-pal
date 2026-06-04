package com.weekendplanner.engine.routing;

public record IntentEvidence(
        boolean timeSignal,
        boolean headcountSignal,
        boolean hasExplicitPlanRequest,
        boolean hasExplorationRequest,
        boolean hasMovieRequest,
        boolean hasNearbyFoodRequest,
        boolean hasReasoningRequest,
        String afterTime
) {
}
