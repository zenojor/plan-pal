package com.weekendplanner.engine.routing;

import com.weekendplanner.engine.understanding.TurnUnderstanding;

public record InitialRouteCommand(
        InitialRouteMode mode,
        double confidence,
        String researchType,
        IntentEvidence evidence,
        String clarificationQuestion,
        TurnUnderstanding understanding
) {
    public InitialRouteCommand(InitialRouteMode mode,
                               double confidence,
                               String researchType,
                               IntentEvidence evidence,
                               String clarificationQuestion) {
        this(mode, confidence, researchType, evidence, clarificationQuestion, null);
    }
}
