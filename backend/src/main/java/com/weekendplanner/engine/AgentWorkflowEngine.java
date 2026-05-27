package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.SseEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class AgentWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowEngine.class);

    private final FastPlanEngine fastPlanEngine;
    private final ReActEngine reactEngine;
    private final PlanExecutionStore executionStore;
    private final IntentExtractor intentExtractor;
    private final PlanPatchExtractor planPatchExtractor;
    private final PlanDeltaExtractor planDeltaExtractor;
    private final PlanEditorEngine planEditorEngine;
    private final ReplacementSearchEngine replacementSearchEngine;
    private final ContextAssembler contextAssembler;
    private final AgentRouter agentRouter;
    private final SessionStateStore sessionStateStore;
    private final ObjectMapper objectMapper;
    private final AgentRuntimeProperties runtime;
    private final CandidateCardService candidateCardService;
    private final PlanPatchFactory patchFactory;
    private final RenderTextService textService;
    private final InitialRequestRouter initialRequestRouter;
    private final ResearchRenderWorkflow researchRenderWorkflow;

    @Autowired
    public AgentWorkflowEngine(FastPlanEngine fastPlanEngine,
                               ReActEngine reactEngine,
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
                               ResearchRenderWorkflow researchRenderWorkflow) {
        this.fastPlanEngine = fastPlanEngine;
        this.reactEngine = reactEngine;
        this.executionStore = executionStore;
        this.intentExtractor = intentExtractor;
        this.planPatchExtractor = planPatchExtractor;
        this.planDeltaExtractor = planDeltaExtractor;
        this.planEditorEngine = planEditorEngine;
        this.replacementSearchEngine = replacementSearchEngine;
        this.contextAssembler = contextAssembler;
        this.agentRouter = agentRouter;
        this.sessionStateStore = sessionStateStore;
        this.objectMapper = objectMapper;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
        this.patchFactory = patchFactory == null ? new PlanPatchFactory(this.runtime) : patchFactory;
        this.textService = textService == null ? new RenderTextService() : textService;
        this.candidateCardService = candidateCardService == null
                ? new CandidateCardService(replacementSearchEngine, this.patchFactory, this.runtime, this.textService)
                : candidateCardService;
        this.initialRequestRouter = initialRequestRouter == null ? new InitialRequestRouter() : initialRequestRouter;
        this.researchRenderWorkflow = researchRenderWorkflow;
    }

    public AgentWorkflowEngine(FastPlanEngine fastPlanEngine,
                               ReActEngine reactEngine,
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
        this(fastPlanEngine, reactEngine, executionStore, intentExtractor, planPatchExtractor,
                planDeltaExtractor, planEditorEngine, replacementSearchEngine, contextAssembler, agentRouter,
                sessionStateStore, objectMapper, new AgentRuntimeProperties(), null, null, null, null, null);
    }

    public PlanResponse createPlan(PlanRequest request) {
        InitialRouteCommand route = initialRequestRouter.route(request.prompt());
        if (route.mode() == InitialRouteMode.RESEARCH_AND_RENDER && researchRenderWorkflow != null) {
            return researchRenderWorkflow.execute(request, route, ignored -> {});
        }
        if (route.mode() == InitialRouteMode.ASK_CLARIFICATION) {
            return createClarificationDraft(request, route, ignored -> {});
        }
        PlanResponse response = fastPlanEngine.executePlan(request);
        rememberDraft(response.planId());
        return response;
    }

    public PlanResponse createPlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        InitialRouteCommand route = initialRequestRouter.route(request.prompt());
        if (route.mode() == InitialRouteMode.RESEARCH_AND_RENDER && researchRenderWorkflow != null) {
            return researchRenderWorkflow.execute(request, route, emitter);
        }
        if (route.mode() == InitialRouteMode.ASK_CLARIFICATION) {
            return createClarificationDraft(request, route, emitter);
        }
        if (route.mode() == InitialRouteMode.AGENT_REASONING && reactEngine != null && intentExtractor != null) {
            PlanIntent intent = intentExtractor.extract(request.prompt());
            reactEngine.executePlanStreaming(request, emitter, intent);
            String planId = request.planId();
            if (planId != null && !planId.isBlank()) rememberDraft(planId);
            return new PlanResponse(planId, request.userId(), "SUCCESS", "Reasoning completed.",
                    List.of(), List.of(), "", "", null, intent, List.of(), "PENDING_CONFIRMATION");
        }
        PlanResponse response = fastPlanEngine.executePlanStreaming(request, emitter);
        rememberDraft(response.planId());
        return response;
    }

    public void executeChat(String planId,
                            String userId,
                            String prompt,
                            String segmentId,
                            String source,
                            String clientActionId,
                            String patchPayload,
                            Consumer<SseEvent> emitter) {
        AgentContext context = contextAssembler.assemble(planId, userId, prompt, segmentId, source, clientActionId);
        PlanPatch directPatch = parsePatchPayload(patchPayload)
                .map(patch -> patchFactory.withSegmentId(patch, segmentId))
                .orElse(null);
        AgentCommand command = directPatch == null
                ? agentRouter.route(context)
                : new AgentCommand("APPLY_PATCH", 1.0, segmentId, null, null, Map.of(),
                null, "APPLY_DIRECT_PATCH", RouteMode.FAST_WORKFLOW, false, null, directPatch);

        if (command.needClarification()) {
            emitClarification(context, command, emitter);
            return;
        }
        if (command.routeMode() == RouteMode.AGENT_REASONING) {
            executeReasoningFallback(context, emitter);
            return;
        }

        switch (command.command()) {
            case "APPLY_CANDIDATE_TO_PLAN" -> applySelectedCandidate(context, command, emitter);
            case "REPLACE_SEGMENT_WITH_CANDIDATES" -> offerReplacementCandidates(context, command, emitter);
            case "EXTEND_PLAN_END_TIME" -> extendPlanEndTime(context, command, emitter);
            case "CANCEL_PENDING_ACTION" -> cancelPendingAction(context, emitter);
            case "APPLY_DIRECT_PATCH" -> handlePatch(context, PlanDelta.fromPatch(command.directPatch()), source, directPatch, emitter);
            default -> applyFeedbackPatch(context, emitter);
        }
    }

    public void rememberDraft(String planId) {
        executionStore.find(planId).ifPresent(sessionStateStore::syncDraft);
    }

    public CandidateCardResult buildCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return candidateCardService.buildCandidateCard(draft, patch);
    }

    private PlanResponse createClarificationDraft(PlanRequest request,
                                                  InitialRouteCommand route,
                                                  Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent intent = intentExtractor == null
                ? new PlanIntent(1, List.of(), "14:00", "18:00", 240, null,
                List.of(), List.of(), null, null, request.prompt(), false)
                : intentExtractor.extract(request.prompt());
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), "");
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);
        String message = route.clarificationQuestion() == null
                ? textService.clarificationFallback()
                : route.clarificationQuestion();
        emitter.accept(new SseEvent("FINISH", 1, message, List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        return new PlanResponse(planId, request.userId(), "SUCCESS", message, List.of(), List.of(),
                "", message, null, intent, List.of(), "PENDING_CONFIRMATION");
    }

    private void applyFeedbackPatch(AgentContext context, Consumer<SseEvent> emitter) {
        if (planEditorEngine == null || planPatchExtractor == null) {
            executeReasoningFallback(context, emitter);
            return;
        }
        PlanDelta delta = planDeltaExtractor != null
                ? planDeltaExtractor.extract(empty(context.userInput()), context.draft().timeline(), context.draft().intent())
                : PlanDelta.fromPatch(planPatchExtractor.extract(empty(context.userInput()),
                context.draft().timeline(), context.draft().intent()));
        PlanPatch patch = patchFactory.withSegmentId(delta.patch(), context.segmentId());
        PlanDelta adjusted = new PlanDelta(delta.operation(), delta.scope(), patch, delta.changedConstraints(),
                delta.lockedSegmentIds(), delta.segmentRequirements(), delta.replanScope(), delta.requiresSearch());
        handlePatch(context, adjusted, context.source(), null, emitter);
    }

    private void handlePatch(AgentContext context,
                             PlanDelta delta,
                             String source,
                             PlanPatch directPatch,
                             Consumer<SseEvent> emitter) {
        PlanPatch patch = delta.patch();
        if (shouldOfferReplacementCandidates(source, patch)) {
            PlanPatch candidatePatch = "puzzle-replace-preview".equals(source)
                    ? patchFactory.replaceForSegment(context.draft(), context.segmentId())
                    : patch;
            emitCandidateCard(context, candidatePatch, emitter);
            return;
        }
        applyDeltaAndMaybeRecommend(context, delta, patch, emitter);
    }

    private void applySelectedCandidate(AgentContext context, AgentCommand command, Consumer<SseEvent> emitter) {
        SessionState state = context.sessionState();
        PendingAction pending = state.pendingAction();
        String candidateSetId = command.candidateSetId() == null && pending != null
                ? pending.candidateSetId()
                : command.candidateSetId();
        CandidateSet candidateSet = state.lastCandidates().stream()
                .filter(set -> set.candidateSetId().equals(candidateSetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate set not found: " + candidateSetId));
        int selectedIndex = command.selectedIndex() == null ? 1 : command.selectedIndex();
        CandidateItem item = candidateSet.items().stream()
                .filter(candidate -> candidate.index() == selectedIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate index not found: " + selectedIndex));
        sessionStateStore.clearPending(context.draft().planId(),
                new RecentEvent(RecentEventType.CANDIDATE_SELECTED, item.poi().name(), Instant.now()));
        applyDeltaAndMaybeRecommend(context, PlanDelta.fromPatch(item.planPatch()), item.planPatch(), emitter);
    }

    private void offerReplacementCandidates(AgentContext context, AgentCommand command, Consumer<SseEvent> emitter) {
        emitCandidateCard(context, patchFactory.replacementFromCommand(context, command), emitter);
    }

    private void extendPlanEndTime(AgentContext context, AgentCommand command, Consumer<SseEvent> emitter) {
        String newEndTime = String.valueOf(command.slots().getOrDefault("newEndTime", context.draft().intent().endTime()));
        applyDeltaAndMaybeRecommend(context,
                patchFactory.editEndTime(context.draft().intent().startTime(), newEndTime),
                patchFactory.keepAndReplan(),
                emitter);
    }

    private void executeReasoningFallback(AgentContext context, Consumer<SseEvent> emitter) {
        if (reactEngine != null && intentExtractor != null) {
            PlanIntent merged = intentExtractor.mergeForAdjustment(context.draft().intent(), context.userInput());
            reactEngine.executePlanStreaming(new PlanRequest(context.draft().userId(), context.userInput(), context.draft().planId()),
                    emitter, merged);
            rememberDraft(context.draft().planId());
            return;
        }
        applyFeedbackPatch(context, emitter);
    }

    private void cancelPendingAction(AgentContext context, Consumer<SseEvent> emitter) {
        sessionStateStore.clearPending(context.draft().planId(),
                new RecentEvent(RecentEventType.PENDING_CANCELLED, context.userInput(), Instant.now()));
        emitter.accept(new SseEvent("FINISH", 1, textService.pendingCancelled(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION"));
    }

    private void emitClarification(AgentContext context, AgentCommand command, Consumer<SseEvent> emitter) {
        emitter.accept(new SseEvent("FINISH", 1,
                command.clarificationQuestion() == null ? textService.clarificationFallback() : command.clarificationQuestion(),
                context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                context.draft().planId(), context.draft().intent(), context.draft().orderIntents(),
                "PENDING_CONFIRMATION"));
    }

    private void applyDeltaAndMaybeRecommend(AgentContext context,
                                             PlanDelta delta,
                                             PlanPatch patch,
                                             Consumer<SseEvent> emitter) {
        if (planEditorEngine == null) {
            executeReasoningFallback(context, emitter);
            return;
        }
        emitter.accept(new SseEvent("START", 0, textService.fastWorkflowStarted(), context.draft().timeline(),
                null, null, null, null, context.draft().planId(), context.draft().intent(),
                context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("ACTION", 1, "PlanEditorEngine.applyDelta: " + patch.editType(),
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));

        PlanResponse response = planEditorEngine.applyDelta(context.draft(), delta);
        PlanExecutionStore.DraftPlan updatedDraft = executionStore.find(response.planId()).orElse(context.draft());
        sessionStateStore.syncDraft(updatedDraft);

        ActionCard candidateCard = null;
        PlanPatch autoPatch = null;
        if (replacementSearchEngine != null) {
            Optional<PlanStep> leisureStep = findAutoRecommendStep(response.timeline());
            if (leisureStep.isPresent()) {
                PlanStep step = leisureStep.get();
                String phase = inferPhaseForTime(step.startTime());
                autoPatch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                        new PlanPatch.Target(step.segmentId(), null, phase, phase, null, null),
                        new PlanPatch.Requirements(List.of(), patch.requirements().avoid(), patch.requirements().prefer(),
                                null, null, null, false),
                        true);
                CandidateCardResult result = candidateCardService.buildCandidateCard(updatedDraft, autoPatch);
                if (result.card().options() != null && !result.card().options().isEmpty()) {
                    candidateCard = result.card();
                    saveCandidateState(updatedDraft, result.candidateSet(), step.segmentId());
                }
            }
        }

        PlanPatch responsePatch = autoPatch == null ? patch : autoPatch;
        emitter.accept(new SseEvent("PLAN_STEP", 2, textService.planUpdated(), response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                responsePatch, candidateCard));
        String summary = candidateCard == null ? response.summary() : response.summary() + textService.autoRecommendationSuffix();
        emitter.accept(new SseEvent("FINISH", 3, summary, response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                responsePatch, candidateCard));
    }

    private void emitCandidateCard(AgentContext context, PlanPatch patch, Consumer<SseEvent> emitter) {
        CandidateCardResult result = candidateCardService.buildCandidateCard(context.draft(), patch);
        saveCandidateState(context.draft(), result.candidateSet(), result.candidateSet().targetSegmentId());
        emitter.accept(new SseEvent("ACTION", 1, "ReplacementSearchEngine.findCandidates",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("OBSERVATION", 2, "Candidate count: " + result.card().options().size(),
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
        emitter.accept(new SseEvent("FINISH", 3, textService.candidatePrompt(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
    }

    private void saveCandidateState(PlanExecutionStore.DraftPlan draft, CandidateSet candidateSet, String targetSegmentId) {
        sessionStateStore.saveCandidates(draft.planId(), draft.userId(), candidateSet,
                new PendingAction("SELECT_CANDIDATE", candidateSet.candidateSetId(), targetSegmentId,
                        List.of("choose index", "more options", "cancel")),
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Recommended " + candidateSet.items().size() + " candidates", Instant.now()));
    }

    private boolean shouldOfferReplacementCandidates(String source, PlanPatch patch) {
        if (replacementSearchEngine == null || patch == null) return false;
        if ("puzzle-replace-preview".equals(source)) return true;
        if (patchFactory.selectedPoiId(patch).isPresent()) return false;
        return ("REPLACE".equalsIgnoreCase(patch.editType()) || "ADD".equalsIgnoreCase(patch.editType()))
                && patch.requiresSearch();
    }

    private Optional<PlanStep> findAutoRecommendStep(List<PlanStep> timeline) {
        return timeline.stream()
                .filter(step -> !step.isTransit())
                .filter(step -> "LEISURE".equalsIgnoreCase(step.phase())
                        || (step.poiName() != null && (step.poiName().contains("自由") || step.poiName().contains("散步"))))
                .filter(step -> step.durationMinutes() >= runtime.getAutoRecommendMinMinutes())
                .max(Comparator.comparingInt(PlanStep::durationMinutes));
    }

    private Optional<PlanPatch> parsePatchPayload(String patchPayload) {
        if (patchPayload == null || patchPayload.isBlank()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(patchPayload, PlanPatch.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid structured patch payload", e);
        }
    }

    private String inferPhaseForTime(String startTime) {
        if (startTime == null || startTime.isBlank()) return "DINING";
        int startMin = toMinutes(startTime);
        if (startMin >= runtime.getDinnerStartMinutes() && startMin < runtime.getDrinksStartMinutes()) return "DINING";
        if (startMin >= runtime.getDrinksStartMinutes()) return "DRINKS";
        return "ACTIVITY";
    }

    private int toMinutes(String time) {
        if (time == null || time.isBlank()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String empty(String value) {
        return value == null ? "" : value;
    }
}
