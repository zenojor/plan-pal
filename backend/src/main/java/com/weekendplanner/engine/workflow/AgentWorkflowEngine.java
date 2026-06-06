package com.weekendplanner.engine.workflow;

import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.graph.PlanPalGraphRuntime;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class AgentWorkflowEngine {

    private final PlanPalGraphRuntime graphRuntime;
    private final WorkflowActionService actions;

    @Autowired
    public AgentWorkflowEngine(PlanPalGraphRuntime graphRuntime, WorkflowActionService actions) {
        this.graphRuntime = graphRuntime;
        this.actions = actions;
    }

    public PlanResponse createPlan(PlanRequest request) {
        return graphRuntime.createPlan(request, ignored -> {});
    }

    public PlanResponse createPlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return graphRuntime.createPlanStreaming(request, event -> {
            if (event != null && event.sseEvent() != null) {
                emitter.accept(event.sseEvent());
            }
        });
    }

    public void executeChat(String planId,
                            String userId,
                            String prompt,
                            String segmentId,
                            String source,
                            String clientActionId,
                            String patchPayload,
                            Consumer<SseEvent> emitter) {
        graphRuntime.executeChat(planId, userId, prompt, segmentId, source, clientActionId, patchPayload,
                event -> {
                    if (event != null && event.sseEvent() != null) {
                        emitter.accept(event.sseEvent());
                    }
                });
    }

    public void rememberDraft(String planId) {
        actions.rememberDraft(planId);
    }

    public CandidateCardResult buildCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return actions.buildCandidateCard(draft, patch);
    }
}
