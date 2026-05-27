package com.weekendplanner.engine;

public record InitialRouteCommand(
        InitialRouteMode mode,
        double confidence,
        String researchType,
        IntentEvidence evidence,
        String clarificationQuestion
) {
}
