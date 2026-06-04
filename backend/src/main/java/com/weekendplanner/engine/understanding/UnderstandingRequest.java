package com.weekendplanner.engine.understanding;

import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.runtime.PlanExecutionStore;

public record UnderstandingRequest(
        String userTurn,
        PendingAction pendingAction,
        SessionState sessionState,
        PlanExecutionStore.DraftPlan draft,
        String source
) {
    public UnderstandingRequest {
        userTurn = userTurn == null ? "" : userTurn;
        source = source == null ? "" : source;
    }

    public static UnderstandingRequest fromContext(AgentContext context, String source) {
        return new UnderstandingRequest(
                context == null ? "" : context.userInput(),
                context == null ? null : context.pendingAction(),
                context == null ? null : context.sessionState(),
                context == null ? null : context.draft(),
                source);
    }
}
