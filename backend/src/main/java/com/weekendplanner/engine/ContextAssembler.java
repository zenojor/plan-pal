package com.weekendplanner.engine;

import org.springframework.stereotype.Component;

@Component
public class ContextAssembler {

    private final PlanExecutionStore executionStore;
    private final SessionStateStore sessionStateStore;

    public ContextAssembler(PlanExecutionStore executionStore, SessionStateStore sessionStateStore) {
        this.executionStore = executionStore;
        this.sessionStateStore = sessionStateStore;
    }

    public AgentContext assemble(String planId,
                                 String userId,
                                 String userInput,
                                 String segmentId,
                                 String source,
                                 String clientActionId) {
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan draft not found: " + planId));
        SessionState state = sessionStateStore.syncDraft(draft);
        return new AgentContext(userInput, draft, state, segmentId, source, clientActionId);
    }
}
