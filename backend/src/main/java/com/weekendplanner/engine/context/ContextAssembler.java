package com.weekendplanner.engine.context;


import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.dto.ConstraintSet;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

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
        requireOwner(draft, userId);
        SessionState state = sessionStateStore.syncDraft(draft);
        return new AgentContext(userInput, draft, state, segmentId, source, clientActionId);
    }

    public ContextPack assemblePack(String planId,
                                    String userId,
                                    String userInput,
                                    String segmentId,
                                    List<String> allowedTools) {
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("Plan draft not found: " + planId));
        requireOwner(draft, userId);
        SessionState state = sessionStateStore.syncDraft(draft);
        ConstraintSet constraints = state.userConstraints() == null
                ? ConstraintSet.fromIntent(draft.intent())
                : state.userConstraints();
        return new ContextPack(userId, planId, userInput, DraftDigest.fromDraft(draft), segmentId,
                state.pendingAction(), state.lastCandidates(), state.recentEvents(), constraints,
                allowedTools == null ? List.of() : allowedTools, 1);
    }

    private void requireOwner(PlanExecutionStore.DraftPlan draft, String userId) {
        if (draft == null) return;
        if (!Objects.equals(draft.userId(), userId)) {
            throw new SecurityException("Plan does not belong to user. planId=" + draft.planId()
                    + ", userId=" + userId);
        }
    }
}
