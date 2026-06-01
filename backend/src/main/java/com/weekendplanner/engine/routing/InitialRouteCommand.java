package com.weekendplanner.engine.routing;

public record InitialRouteCommand(
        InitialRouteMode mode,
        double confidence,
        String researchType,
        IntentEvidence evidence,
        String clarificationQuestion
) {
}
