package com.weekendplanner.engine.context;


import com.weekendplanner.engine.runtime.PlanExecutionStore;
public record AgentContext(
        String userInput,
        PlanExecutionStore.DraftPlan draft,
        SessionState sessionState,
        String segmentId,
        String source,
        String clientActionId
) {
    public PendingAction pendingAction() {
        return sessionState == null ? null : sessionState.pendingAction();
    }
}
