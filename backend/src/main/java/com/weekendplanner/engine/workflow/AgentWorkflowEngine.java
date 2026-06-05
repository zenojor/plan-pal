package com.weekendplanner.engine.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.candidate.CandidateCardService;
import com.weekendplanner.engine.context.ContextAssembler;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.interaction.ConversationalQaService;
import com.weekendplanner.engine.interaction.InteractionRouter;
import com.weekendplanner.engine.patch.PlanDeltaExtractor;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.patch.PlanPatchExtractor;
import com.weekendplanner.engine.patch.PlanPatchFactory;
import com.weekendplanner.engine.planning.RenderTextService;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.routing.AgentRouter;
import com.weekendplanner.engine.routing.InitialRequestRouter;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class AgentWorkflowEngine {

    private final WorkflowActionService actions;

    @Autowired
    public AgentWorkflowEngine(WorkflowActionService actions) {
        this.actions = actions;
    }

    public AgentWorkflowEngine(FastPlanEngine fastPlanEngine,
                               PlanExecutionStore executionStore,
                               IntentExtractor intentExtractor,
                               PlanPatchExtractor planPatchExtractor,
                               PlanDeltaExtractor planDeltaExtractor,
                               PlanEditorEngine planEditorEngine,
                               ReplacementSearchEngine replacementSearchEngine,
                               ContextAssembler contextAssembler,
                               AgentRouter agentRouter,
                               SessionStateStore sessionStateStore,
                               ObjectMapper objectMapper,
                               AgentRuntimeProperties runtime,
                               CandidateCardService candidateCardService,
                               PlanPatchFactory patchFactory,
                               RenderTextService textService,
                               InitialRequestRouter initialRequestRouter,
                               ResearchRenderWorkflow researchRenderWorkflow,
                               ConsultationWorkflow consultationWorkflow,
                               InteractionRouter interactionRouter,
                               ConversationalQaService conversationalQaService) {
        this.actions = new WorkflowActionService(fastPlanEngine, executionStore, intentExtractor,
                planPatchExtractor, planDeltaExtractor, planEditorEngine, replacementSearchEngine,
                contextAssembler, agentRouter, sessionStateStore, objectMapper, runtime,
                candidateCardService, patchFactory, textService, initialRequestRouter,
                researchRenderWorkflow, consultationWorkflow, interactionRouter, conversationalQaService);
    }

    public AgentWorkflowEngine(FastPlanEngine fastPlanEngine,
                               PlanExecutionStore executionStore,
                               IntentExtractor intentExtractor,
                               PlanPatchExtractor planPatchExtractor,
                               PlanDeltaExtractor planDeltaExtractor,
                               PlanEditorEngine planEditorEngine,
                               ReplacementSearchEngine replacementSearchEngine,
                               ContextAssembler contextAssembler,
                               AgentRouter agentRouter,
                               SessionStateStore sessionStateStore,
                               ObjectMapper objectMapper) {
        this(fastPlanEngine, executionStore, intentExtractor, planPatchExtractor,
                planDeltaExtractor, planEditorEngine, replacementSearchEngine, contextAssembler, agentRouter,
                sessionStateStore, objectMapper, new AgentRuntimeProperties(), null, null, null,
                null, null, null, null, null);
    }

    public AgentWorkflowEngine(FastPlanEngine fastPlanEngine,
                               PlanExecutionStore executionStore,
                               IntentExtractor intentExtractor,
                               PlanPatchExtractor planPatchExtractor,
                               PlanDeltaExtractor planDeltaExtractor,
                               PlanEditorEngine planEditorEngine,
                               ReplacementSearchEngine replacementSearchEngine,
                               ContextAssembler contextAssembler,
                               AgentRouter agentRouter,
                               SessionStateStore sessionStateStore,
                               ObjectMapper objectMapper,
                               AgentRuntimeProperties runtime,
                               CandidateCardService candidateCardService,
                               PlanPatchFactory patchFactory,
                               RenderTextService textService,
                               InitialRequestRouter initialRequestRouter,
                               ResearchRenderWorkflow researchRenderWorkflow,
                               ConsultationWorkflow consultationWorkflow) {
        this(fastPlanEngine, executionStore, intentExtractor, planPatchExtractor,
                planDeltaExtractor, planEditorEngine, replacementSearchEngine, contextAssembler, agentRouter,
                sessionStateStore, objectMapper, runtime, candidateCardService, patchFactory, textService,
                initialRequestRouter, researchRenderWorkflow, consultationWorkflow, null, null);
    }

    public PlanResponse createPlan(PlanRequest request) {
        return actions.createPlan(request);
    }

    public PlanResponse createPlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return actions.createPlanStreaming(request, emitter);
    }

    public void executeChat(String planId,
                            String userId,
                            String prompt,
                            String segmentId,
                            String source,
                            String clientActionId,
                            String patchPayload,
                            Consumer<SseEvent> emitter) {
        actions.executeChat(planId, userId, prompt, segmentId, source, clientActionId, patchPayload, emitter);
    }

    public void rememberDraft(String planId) {
        actions.rememberDraft(planId);
    }

    public CandidateCardResult buildCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return actions.buildCandidateCard(draft, patch);
    }
}
