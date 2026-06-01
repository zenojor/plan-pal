package com.weekendplanner.engine.interaction;

import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.runtime.PlanExecutionStore;

public record ContextualQaRequest(
        String userMessage,
        PlanExecutionStore.DraftPlan draft,
        SessionState sessionState
) {
}
