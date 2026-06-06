package com.weekendplanner.engine.workflow;

import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.ContextAssembler;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.RecentEvent;
import com.weekendplanner.engine.context.RecentEventType;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.patch.PlanDeltaExtractor;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.patch.PlanPatchExtractor;
import com.weekendplanner.engine.patch.PlanPatchFactory;
import com.weekendplanner.engine.planning.RenderTextService;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.SlotCollectionService;
import com.weekendplanner.engine.runtime.AgentCommand;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.runtime.RouteMode;
import com.weekendplanner.engine.candidate.CandidateCardResult;
import com.weekendplanner.engine.candidate.CandidateCardService;
import com.weekendplanner.engine.candidate.CandidateItem;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.routing.AgentRouter;
import com.weekendplanner.engine.routing.InitialRequestRouter;
import com.weekendplanner.engine.routing.InitialRouteCommand;
import com.weekendplanner.engine.routing.InitialRouteMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.SseEvent;

import com.weekendplanner.engine.interaction.InteractionRouter;
import com.weekendplanner.engine.interaction.InteractionDecision;
import com.weekendplanner.engine.interaction.InteractionCommand;
import com.weekendplanner.engine.interaction.PendingSlotFiller;
import com.weekendplanner.engine.interaction.PendingSlotPatch;
import com.weekendplanner.engine.interaction.ConversationalQaService;
import com.weekendplanner.engine.interaction.ContextualQaRequest;
import com.weekendplanner.engine.interaction.ContextualQaResponse;
import com.weekendplanner.engine.understanding.FallbackSlotExtractor;
import com.weekendplanner.engine.understanding.TurnUnderstanding;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class WorkflowActionService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowActionService.class);

    private final FastPlanEngine fastPlanEngine;
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
    private final ConsultationWorkflow consultationWorkflow;
    private final SlotCollectionService slotCollectionService;

    private final InteractionRouter interactionRouter;
    private final ConversationalQaService conversationalQaService;
    private final PendingSlotFiller pendingSlotFiller = new PendingSlotFiller();
    private final FallbackSlotExtractor fallbackSlotExtractor = new FallbackSlotExtractor();

    @Autowired
    public WorkflowActionService(FastPlanEngine fastPlanEngine,
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
        this.fastPlanEngine = fastPlanEngine;
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
        this.consultationWorkflow = consultationWorkflow;
        this.interactionRouter = interactionRouter == null
                ? new InteractionRouter((org.springframework.ai.chat.model.ChatModel) null, objectMapper, new com.weekendplanner.engine.routing.RouterRuleBook())
                : interactionRouter;
        this.conversationalQaService = conversationalQaService == null
                ? new ConversationalQaService((org.springframework.ai.chat.model.ChatModel) null)
                : conversationalQaService;
        this.slotCollectionService = new SlotCollectionService();
    }


    public InitialRouteCommand routeInitial(PlanRequest request) {
        return initialRequestRouter.route(request == null ? "" : request.prompt());
    }

    public PlanResponse consultInitial(PlanRequest request,
                                       InitialRouteCommand route,
                                       Consumer<SseEvent> emitter) {
        return consultationWorkflow == null
                ? createClarificationDraft(request, route, emitter)
                : consultationWorkflow.start(request, route, emitter);
    }

    public PlanResponse researchInitial(PlanRequest request,
                                        InitialRouteCommand route,
                                        Consumer<SseEvent> emitter) {
        return researchRenderWorkflow == null
                ? createClarificationDraft(request, route, emitter)
                : researchRenderWorkflow.execute(request, route, emitter);
    }

    public PlanResponse createDirectPlan(PlanRequest request, Consumer<SseEvent> emitter, boolean streaming) {
        PlanResponse response = streaming
                ? fastPlanEngine.executePlanStreaming(request, emitter)
                : fastPlanEngine.executePlan(request);
        rememberDraft(response.planId());
        return response;
    }

    public boolean shouldOfferInitialPlanChoices(PlanRequest request) {
        return request != null && shouldOfferPlanChoices(request.prompt());
    }



    public ContextPack assembleChatContextPack(String planId,
                                              String userId,
                                              String prompt,
                                              String segmentId) {
        return contextAssembler.assemblePack(planId, userId, prompt, segmentId, java.util.List.of());
    }

    public String mergeInteractionSource(String source, String clientActionId) {
        return mergeSource(source, clientActionId);
    }

    public InteractionDecision routeInteraction(ContextPack context,
                                                String interactionSource,
                                                String patchPayload,
                                                Consumer<SseEvent> emitter) {
        InteractionDecision decision = interactionRouter.route(context, interactionSource, patchPayload);
        emitTool(emitter, "ACTION", 1, context, "interaction.route: route chat turn");
        emitTool(emitter, "OBSERVATION", 1, context, "interaction.route result: command=" + decision.command()
                + ", confidence=" + decision.confidence() + ", reason=" + decision.reason());
        return decision;
    }

    public void startNewPlanFromChat(String userId, String prompt, Consumer<SseEvent> emitter) {
        PlanResponse response = fastPlanEngine.executePlanStreaming(new PlanRequest(userId, prompt), emitter);
        rememberDraft(response.planId());
    }

    public boolean continuePendingWorkflow(ContextPack context,
                                           InteractionDecision decision,
                                           String interactionSource,
                                           String prompt,
                                           PlanPatch directPatch,
                                           Consumer<SseEvent> emitter) {
        if (decision == null || decision.command() != InteractionCommand.CONTINUE_WORKFLOW) return false;
        PendingAction pending = context.pendingAction();
        if (directPatch == null && isPendingType(pending, "MOVIE_SCHEDULING")) {
            continueMovieScheduling(context, decision.pendingSlotPatch(), emitter);
            return true;
        }
        if (directPatch == null && isPendingType(pending, "PLAN_SLOT_FILLING")) {
            continueLockedCandidatePlanning(context, decision.pendingSlotPatch(), emitter);
            return true;
        }
        if (consultationWorkflow != null && isPreferenceSelection(interactionSource, prompt, context)) {
            consultationWorkflow.continueAfterPreference(context, emitter);
            return true;
        }
        if (directPatch == null && consultationWorkflow != null && isConsultationContextTurn(context)) {
            consultationWorkflow.continueAfterContext(context, emitter);
            return true;
        }
        return false;
    }

    public PlanPatch parseDirectPatch(String patchPayload, String interactionSource, String segmentId) {
        return parsePatchPayload(patchPayload, interactionSource)
                .map(patch -> patchFactory.withSegmentId(patch, segmentId))
                .orElse(null);
    }

    public void runAgentCommandPath(ContextPack context,
                                    InteractionDecision decision,
                                    String source,
                                    PlanPatch directPatch,
                                    Consumer<SseEvent> emitter) {
        AgentCommand command = directPatch == null
                ? agentRouter.route(context)
                : new AgentCommand("APPLY_PATCH", 1.0, context.selectedSegmentId(), null, null, Map.of(),
                null, "APPLY_DIRECT_PATCH", RouteMode.FAST_WORKFLOW, false, null, directPatch);
        emitTool(emitter, "ACTION", 2, context, "router.decide: workflow command");
        emitTool(emitter, "OBSERVATION", 2, context, "router.decide result: intent=" + command.intent()
                + ", command=" + command.command() + ", mode=" + command.routeMode());

        if (command.needClarification()) {
            emitClarification(context, command, emitter);
            return;
        }
        switch (command.command()) {
            case "APPLY_CANDIDATE_TO_PLAN" -> applySelectedCandidate(context, command, emitter);
            case "REPLACE_SEGMENT_WITH_CANDIDATES" -> offerReplacementCandidates(context, command, emitter);
            case "EXTEND_PLAN_END_TIME" -> extendPlanEndTime(context, command, emitter);
            case "CANCEL_PENDING_ACTION" -> cancelPendingAction(context, emitter);
            case "APPLY_DIRECT_PATCH" -> handlePatch(context, PlanDelta.fromPatch(command.directPatch()), source, directPatch, emitter);
            default -> applyFeedbackPatch(context, source, decision == null ? null : decision.understanding(), emitter);
        }
    }



    private String mergeSource(String source, String clientActionId) {
        String left = source == null ? "" : source.trim();
        String right = clientActionId == null ? "" : clientActionId.trim();
        return (left + " " + right).trim();
    }

    public void rememberDraft(String planId) {
        executionStore.find(planId).ifPresent(sessionStateStore::syncDraft);
    }

    public CandidateCardResult buildCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return candidateCardService.buildCandidateCard(draft, patch);
    }

    private boolean isPendingType(PendingAction pending, String type) {
        return pending != null && type.equalsIgnoreCase(pending.type());
    }

    private PlanExecutionStore.DraftPlan getDraft(ContextPack context) {
        return executionStore.find(context.planId())
                .orElseThrow(() -> new IllegalArgumentException("Draft not found: " + context.planId()));
    }

    private SessionState getSessionState(ContextPack context) {
        return sessionStateStore.find(context.planId())
                .orElseGet(() -> sessionStateStore.getOrCreate(context.planId(), context.userId()));
    }

    private void continueMovieScheduling(ContextPack context, PendingSlotPatch slotPatch, Consumer<SseEvent> emitter) {
        PendingAction pending = mergePendingSlots(context, "MOVIE_SCHEDULING", slotPatch);
        if (pending.selectedPatch() == null) {
            emitPendingQuestion(context, pending, "我还记得你在电影流程里。你可以重新选一场电影，或告诉我想看的电影名/场次。",
                    List.of("TIME_RANGE"), emitter);
            return;
        }
        if (!hasSlot(pending, "startTime") && !hasMovieTime(pending.selectedPatch())) {
            emitPendingQuestion(context, pending, null, List.of("TIME_RANGE", "LOCATION_SCOPE"), emitter);
            return;
        }
        if (!hasSlot(pending, "headcount")) {
            emitPendingQuestion(context, pending, null, List.of("HEADCOUNT"), emitter);
            return;
        }
        if (planEditorEngine == null) {
            emitPendingQuestion(context, pending, textService.clarificationFallback(), List.of("TIME_RANGE"), emitter);
            return;
        }

        PlanExecutionStore.DraftPlan draft = getDraft(context);
        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: movie_schedule",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                pending.selectedPatch(), null));
        PlanResponse response = planEditorEngine.applyPendingSelectedPatch(draft, pending);
        emitPendingPlanResponse(context, pending, response, emitter);
    }

    private void continueLockedCandidatePlanning(ContextPack context, PendingSlotPatch slotPatch, Consumer<SseEvent> emitter) {
        PendingAction pending = mergePendingSlots(context, "PLAN_SLOT_FILLING", slotPatch);
        if (pending.selectedPatch() == null) {
            emitPendingQuestion(context, pending, "我还记得你选过一个候选，但缺少可执行的地点信息。请重新选一次候选。",
                    List.of("LOCATION_SCOPE"), emitter);
            return;
        }
        if (!hasSlot(pending, "startTime")) {
            emitPendingQuestion(context, pending, null, List.of("TIME_RANGE"), emitter);
            return;
        }
        if (!hasSlot(pending, "headcount")) {
            emitPendingQuestion(context, pending, null, List.of("HEADCOUNT"), emitter);
            return;
        }
        if (!hasSlot(pending, "locationScope")) {
            pending = pending.mergeCollectedSlots(Map.of("locationScope", "NEARBY", "assumed:locationScope", true));
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Assumed nearby scope for locked candidate workflow", Instant.now()));
        }
        if (planEditorEngine == null) {
            emitPendingQuestion(context, pending, textService.clarificationFallback(), List.of("TIME_RANGE"), emitter);
            return;
        }

        PlanExecutionStore.DraftPlan draft = getDraft(context);
        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: locked_candidate_plan",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                pending.selectedPatch(), null));
        PlanResponse response = planEditorEngine.applyLockedCandidatePlan(draft, pending);
        emitPendingPlanResponse(context, pending, response, emitter);
    }

    private PendingAction mergePendingSlots(ContextPack context, String expectedType, PendingSlotPatch existingSlotPatch) {
        PendingAction pending = context.pendingAction();
        if (pending == null) return null;
        PendingSlotPatch slotPatch = existingSlotPatch == null
                ? pendingSlotFiller.extract(pending, context.userTurn(), getSessionState(context))
                : existingSlotPatch;
        PendingAction merged = pending.withType(expectedType).mergeCollectedSlots(slotPatch.slots());
        sessionStateStore.savePending(context.planId(), context.userId(), merged,
                new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                        slotPatch.reason().isBlank() ? "Pending workflow resumed" : slotPatch.reason(), Instant.now()));
        return merged;
    }

    private void emitPendingPlanResponse(ContextPack context,
                                         PendingAction pending,
                                         PlanResponse response,
                                         Consumer<SseEvent> emitter) {
        if (response.conflicts().isEmpty() && response.timeline() != null && !response.timeline().isEmpty()) {
            sessionStateStore.clearPending(context.planId(),
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow completed: " + pending.type(), Instant.now()));
        } else {
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow kept after validation", Instant.now()));
        }
        emitter.accept(new SseEvent("OBSERVATION", 2, "constraint.validate: conflicts=" + response.conflicts().size(),
                response.timeline(), response.status(), response.orderGroupId(), response.notificationText(),
                response.degradationNote(), response.planId(), response.intent(), response.orderIntents(),
                response.executionStatus(), pending.selectedPatch(), null));
        emitter.accept(new SseEvent("PLAN_STEP", 3, "timeline.update: pending workflow resumed",
                response.timeline(), response.status(), response.orderGroupId(), response.notificationText(),
                response.degradationNote(), response.planId(), response.intent(), response.orderIntents(),
                response.executionStatus(), pending.selectedPatch(), null));
        emitter.accept(new SseEvent("FINISH", 4, response.summary(), response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                pending.selectedPatch(), null, null, response.conflicts(), response.repairOptions(),
                response.version(), response.planStatus(), response.weather(), response.summary()));
    }

    private void emitPendingQuestion(ContextPack context,
                                     PendingAction pending,
                                     String message,
                                     List<String> missingSlots,
                                     Consumer<SseEvent> emitter) {
        if (pending != null) {
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow needs more context: " + pending.type(), Instant.now()));
        }
        SlotCollectionService.SlotCollectionPrompt prompt = slotCollectionService.forSlots(
                context.planId(),
                missingSlots == null || missingSlots.isEmpty()
                        ? slotCollectionService.missingSlots(pending)
                        : missingSlots);
        String content = message == null || message.isBlank() ? prompt.message() : message;
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        emitter.accept(new SseEvent("FINISH", 2, content, context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                pending == null ? null : pending.selectedPatch(), prompt.card()));
    }

    private boolean hasSlot(PendingAction pending, String key) {
        return pending != null
                && pending.collectedSlots() != null
                && pending.collectedSlots().get(key) != null
                && !String.valueOf(pending.collectedSlots().get(key)).isBlank();
    }

    private boolean hasMovieTime(PlanPatch patch) {
        return selectedMetadata(patch, "MOVIE_TIME:").filter(value -> !value.isBlank()).isPresent();
    }

    public void answerContextualQuestion(ContextPack context, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        ContextualQaResponse response = conversationalQaService.answer(new ContextualQaRequest(
                context.userTurn(), draft, getSessionState(context)));
        if (draft != null) {
            sessionStateStore.savePending(draft.planId(), draft.userId(),
                    getSessionState(context).pendingAction(),
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Q&A Turn - User: " + context.userTurn() + " | Bot: " + response.answer(), Instant.now()));
        }
        emitter.accept(new SseEvent("FINISH", 2, response.answer(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                null, response.actionCard()));
    }

    private boolean isPreferenceSelection(String source, String prompt, ContextPack context) {
        if (source != null && source.contains("SELECT_PREFERENCE")) return true;
        if (prompt != null && prompt.toUpperCase().contains("PREFERENCE:")) return true;
        PendingAction pending = context.pendingAction();
        return pending != null
                && "SELECT_PREFERENCE".equalsIgnoreCase(pending.type())
                && looksLikePreferenceReply(prompt);
    }

    private boolean looksLikePreferenceReply(String prompt) {
        String text = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);
        return text.contains("轻松")
                || text.contains("低压力")
                || text.contains("话题")
                || text.contains("尴尬")
                || text.contains("仪式")
                || text.contains("预算")
                || text.contains("便宜")
                || text.contains("下雨")
                || text.contains("室内")
                || text.contains("互动")
                || text.contains("relaxed")
                || text.contains("topic")
                || text.contains("ritual")
                || text.contains("budget")
                || text.contains("weather");
    }

    private boolean isConsultationContextTurn(ContextPack context) {
        PendingAction pending = context.pendingAction();
        return pending != null && "ASK_CONTEXT".equalsIgnoreCase(pending.type());
    }

    public PlanResponse answerInitialQuestion(PlanRequest request, Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        ContextualQaResponse qa = conversationalQaService.answer(new ContextualQaRequest(
                request.prompt(), null, null));
        String answer = qa.answer();
        emitter.accept(new SseEvent("FINISH", 1, answer, List.of(), "SUCCESS", "", "",
                null, planId, null, List.of(), "CHAT_ONLY", null, null));
        return new PlanResponse(planId, request.userId(), "SUCCESS", answer, List.of(), List.of(),
                "", answer, null, null, List.of(), "CHAT_ONLY");
    }

    public PlanResponse createClarificationDraft(PlanRequest request,
                                                  InitialRouteCommand route,
                                                  Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt(), route.understanding());
        PlanIntent intent = extracted == null
                ? new PlanIntent(1, List.of(), null, null, 0, null,
                List.of(), List.of(), null, null, request.prompt(), false)
                : new PlanIntent(extracted.headcount(), extracted.participants(), null, null, 0,
                extracted.sceneType(), extracted.requestedSegments(), extracted.dietaryConstraints(),
                extracted.drinkPreference(), extracted.locationScope(), request.prompt(), extracted.pace(),
                extracted.budgetLevel(), extracted.hasChildren(), extracted.childAge(),
                extracted.preferredTransportMode(), extracted.avoid(), extracted.mustHave(),
                extracted.weatherSensitive(), true);
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), "");
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);
        boolean missingTime = route.evidence() == null || !route.evidence().timeSignal();
        boolean missingHeadcount = route.evidence() == null || !route.evidence().headcountSignal();
        SlotCollectionService.SlotCollectionPrompt slotPrompt =
                slotCollectionService.forInitial(planId, missingTime, missingHeadcount);
        ActionCard card = slotPrompt.card();
        String message = slotPrompt.message();
        emitter.accept(new SseEvent("FINISH", 1, message, List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION", null, card));
        return new PlanResponse(planId, request.userId(), "SUCCESS", message, List.of(), List.of(),
                "", message, null, intent, List.of(), "PENDING_CONFIRMATION");
    }

    private String slotCollectionMessage(boolean missingTime, boolean missingHeadcount) {
        if (missingTime && missingHeadcount) {
            return "\u5148\u9009\u4e00\u4e0b\u51fa\u884c\u65f6\u95f4\uff0c\u518d\u544a\u8bc9\u6211\u51e0\u4e2a\u4eba\u53bb\u3002";
        }
        if (missingTime) {
            return "\u5148\u9009\u4e00\u4e0b\u51fa\u884c\u65f6\u95f4\u3002";
        }
        if (missingHeadcount) {
            return "\u51e0\u4e2a\u4eba\u4e00\u8d77\u53bb\uff1f";
        }
        return "\u8fd8\u5dee\u4e00\u70b9\u4fe1\u606f\uff0c\u8865\u4e00\u4e0b\u6211\u518d\u7ee7\u7eed\u5b89\u6392\u3002";
    }

    public PlanResponse createPlanChoiceDraft(PlanRequest request,
                                               InitialRouteCommand route,
                                               Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt(), route.understanding());
        PlanIntent intent = extracted == null
                ? new PlanIntent(1, List.of(), null, null, 0, null,
                List.of(), List.of(), null, null, request.prompt(), false)
                : new PlanIntent(extracted.headcount(), extracted.participants(), extracted.startTime(),
                extracted.endTime(), extracted.totalMinutes(), extracted.sceneType(),
                extracted.requestedSegments(), extracted.dietaryConstraints(), extracted.drinkPreference(),
                extracted.locationScope(), request.prompt(), extracted.pace(), extracted.budgetLevel(),
                extracted.hasChildren(), extracted.childAge(), extracted.preferredTransportMode(),
                extracted.avoid(), extracted.mustHave(), extracted.weatherSensitive(), false);

        ActionCard card = planChoiceCard(planId, intent);
        String message = "我先给你 3 个方向，选一个后再把对应地点放进拼图。";
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), message);
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);

        emitter.accept(new SseEvent("START", 0, "plan.options: prepare route choices", List.of(),
                null, null, null, null, planId, intent, List.of(), "OPTIONS_READY", null, card));
        emitter.accept(new SseEvent("FINISH", 1, message, List.of(), "SUCCESS", "", message,
                null, planId, intent, List.of(), "OPTIONS_READY", null, card));
        return new PlanResponse(planId, request.userId(), "SUCCESS", message, List.of(), List.of(),
                "", message, null, intent, List.of(), "OPTIONS_READY");
    }

    private boolean shouldOfferPlanChoices(String prompt) {
        String text = prompt == null ? "" : prompt.toUpperCase(Locale.ROOT);
        if (text.contains("[BUILD_SELECTED_PLAN]") || text.contains("BUILD_SELECTED_PLAN")) {
            return false;
        }
        return !text.matches(".*\\d{1,2}\\s*[:：]\\s*\\d{0,2}.*");
    }

    private ActionCard slotCollectionCard(String planId, boolean missingTime, boolean missingHeadcount) {
        if (!missingTime && !missingHeadcount) {
            missingTime = true;
            missingHeadcount = true;
        }
        List<ActionCard.ActionOption> options = new ArrayList<>();
        if (missingTime) {
            options.add(slotOption("slot-time-morning", "上午 10:00-12:30", "适合轻量活动或早午餐。",
                    "上午 10:00 到 12:30", "SLOT_TIME_RANGE"));
            options.add(slotOption("slot-time-afternoon", "下午 14:00-18:00", "默认下午见面时使用这个完整可执行时间段。",
                    "下午 14:00 到 18:00", "SLOT_TIME_RANGE"));
            options.add(slotOption("slot-time-evening", "晚上 19:00-22:00", "适合晚餐、电影或夜间轻活动。",
                    "晚上 19:00 到 22:00", "SLOT_TIME_RANGE"));
        }
        if (missingHeadcount) {
            options.add(slotOption("slot-headcount-1", "1 人", "一个人自由安排。", "总共 1 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-2", "2 人", "两个人一起。", "总共 2 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-3", "3 人", "三个人一起。", "总共 3 个人", "SLOT_HEADCOUNT"));
            options.add(slotOption("slot-headcount-4", "4 人", "四个人一起。", "总共 4 个人", "SLOT_HEADCOUNT"));
        }
        return new ActionCard("slot-collection-" + planId, "补充出行信息",
                "\u5148\u8865\u9f50\u7f3a\u5931\u9879\uff0c\u6211\u518d\u7ee7\u7eed\u5b89\u6392\u3002",
                options, "\u4e5f\u53ef\u4ee5\u76f4\u63a5\u8f93\u5165\u65f6\u95f4\u3001\u4eba\u6570\u6216\u8303\u56f4",
                true, "SLOT_COLLECTION");
    }

    private ActionCard.ActionOption slotOption(String id,
                                               String label,
                                               String description,
                                               String prompt,
                                               String optionKind) {
        return new ActionCard.ActionOption(id, label, description, "SET_SLOT", null, prompt,
                null, List.of(), null, optionKind);
    }

    private ActionCard planChoiceCard(String planId, PlanIntent intent) {
        List<PlanChoiceSpec> specs = planChoiceSpecs(intent);
        Set<String> used = new LinkedHashSet<>();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        for (int i = 0; i < specs.size() && options.size() < 3; i++) {
            ActionCard.ActionOption option = planChoiceOption(planId, i + 1, specs.get(i), intent, used);
            if (option.poiIds() != null && !option.poiIds().isEmpty()) {
                options.add(option);
            }
        }
        return new ActionCard("plan-choice-" + planId, "选择一个方案来构建计划",
                "先选方向，我再把相应地点放进拼图并生成可执行时间线。",
                options, "也可以补充你想调整的方向，比如更室内、少排队、先吃饭",
                true, "PLAN_CHOICE");
    }

    private List<PlanChoiceSpec> planChoiceSpecs(PlanIntent intent) {
        boolean family = intent != null && (intent.hasChildren() || intent.childAge() != null
                || containsAny(intent.participants(), "孩子", "亲子", "family"));
        if (family) {
            return List.of(
                    new PlanChoiceSpec("亲子轻松吃逛", "先放电，再找稳定好吃的轻食或正餐。",
                            List.of("CHILD_FRIENDLY", "INDOOR", "NEARBY"),
                            List.of("FAMILY_STYLE", "CHILD_FRIENDLY", "NEARBY")),
                    new PlanChoiceSpec("室内稳妥备选", "优先选择不太受天气影响、节奏更稳的地点。",
                            List.of("INDOOR", "CHILD_FRIENDLY", "QUIET"),
                            List.of("QUIET", "DESSERT", "NEARBY")),
                    new PlanChoiceSpec("散步放电好吃", "安排轻松散步或户外活动，再接一顿好吃的。",
                            List.of("PARK", "CHILD_FRIENDLY", "NEARBY"),
                            List.of("SOCIAL_DINING", "NEARBY"))
            );
        }
        return List.of(
                new PlanChoiceSpec("轻松吃逛路线", "低压力活动加一顿好吃的，适合随性出门。",
                        List.of("INDOOR", "NEARBY"),
                        List.of("SOCIAL_DINING", "NEARBY")),
                new PlanChoiceSpec("聊天补能路线", "找适合坐下来的活动或咖啡甜品，节奏更慢。",
                        List.of("QUIET", "INDOOR"),
                        List.of("QUIET", "DESSERT", "NEARBY")),
                new PlanChoiceSpec("散步探索路线", "更多本地感和步行探索，保留吃饭节点。",
                        List.of("PARK", "NEARBY"),
                        List.of("SOCIAL_DINING", "NEARBY"))
        );
    }

    private ActionCard.ActionOption planChoiceOption(String planId,
                                                     int index,
                                                     PlanChoiceSpec spec,
                                                     PlanIntent intent,
                                                     Set<String> used) {
        List<PoiDto> pois = new ArrayList<>();
        findPlanChoiceCandidate("ACTIVITY", spec.activityTags(), intent, used).ifPresent(pois::add);
        findPlanChoiceCandidate("DINING", spec.diningTags(), intent, used).ifPresent(pois::add);
        List<String> poiIds = pois.stream().map(PoiDto::poiId).toList();
        used.addAll(poiIds);
        String route = pois.stream().map(PoiDto::name).reduce((left, right) -> left + " -> " + right).orElse("");
        String description = route.isBlank() ? spec.description() : spec.description() + " 推荐：" + route + "。";
        return new ActionCard.ActionOption("plan-choice-" + planId + "-" + index,
                "方案 " + index + "：" + spec.title(), description, "BUILD_PLAN", null,
                "BUILD_PLAN:choice-" + index, null, poiIds, null, "PLAN_CHOICE");
    }

    private Optional<PoiDto> findPlanChoiceCandidate(String phase,
                                                     List<String> tags,
                                                     PlanIntent intent,
                                                     Set<String> used) {
        if (replacementSearchEngine == null) return Optional.empty();
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), tags, null, null, null, false),
                true);
        List<PoiDto> candidates = replacementSearchEngine.findCandidates(phase, patch, intent, used, 3);
        if (candidates.isEmpty() && used != null && !used.isEmpty()) {
            candidates = replacementSearchEngine.findCandidates(phase, patch, intent, Set.of(), 3);
        }
        return candidates.stream().findFirst();
    }

    private boolean containsAny(List<String> values, String... needles) {
        if (values == null || values.isEmpty()) return false;
        String text = String.join(" ", values).toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && text.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private void applyFeedbackPatch(ContextPack context, String source, TurnUnderstanding understanding, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        if (planEditorEngine == null || planPatchExtractor == null) {
            emitClarification(context, new AgentCommand("MODIFY_PLAN", 0.5, context.selectedSegmentId(), null, null,
                    Map.of(), null, "CLARIFY", RouteMode.FAST_WORKFLOW, true,
                    textService.clarificationFallback(), null), emitter);
            return;
        }
        PlanDelta delta = planDeltaExtractor != null
                ? planDeltaExtractor.extract(empty(context.userTurn()), context.draft().timeline(),
                draft.intent(), understanding)
                : PlanDelta.fromPatch(planPatchExtractor.extract(empty(context.userTurn()),
                context.draft().timeline(), draft.intent(), understanding));
        PlanPatch patch = patchFactory.withSegmentId(delta.patch(), context.selectedSegmentId());
        PlanDelta adjusted = new PlanDelta(delta.operation(), delta.scope(), patch, delta.changedConstraints(),
                delta.lockedSegmentIds(), delta.segmentRequirements(), delta.replanScope(), delta.requiresSearch());
        handlePatch(context, adjusted, source, null, emitter);
    }

    private void handlePatch(ContextPack context,
                             PlanDelta delta,
                             String source,
                             PlanPatch directPatch,
                             Consumer<SseEvent> emitter) {
        PlanPatch patch = delta.patch();
        if (shouldDeferPatchInConsultation(context, patch)) {
            emitConsultationPatchDeferral(context, patch, emitter);
            return;
        }
        if (shouldOfferReplacementCandidates(source, patch)) {
            PlanExecutionStore.DraftPlan draft = getDraft(context);
            PlanPatch candidatePatch = "puzzle-replace-preview".equals(source)
                    ? patchFactory.replaceForSegment(draft, context.selectedSegmentId())
                    : patch;
            emitCandidateCard(context, candidatePatch, emitter);
            return;
        }
        applyDeltaAndMaybeRecommend(context, delta, patch, emitter);
    }

    private boolean shouldDeferPatchInConsultation(ContextPack context, PlanPatch patch) {
        if (context == null || patch == null) return false;
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        PlanIntent intent = draft.intent();
        if (intent == null || !intent.isConsultingMode()) return false;
        if (patch.requirements() != null && patch.requirements().prefer().contains("CONTEXT_READY")
                && hasConcretePlanningWindow(intent)) {
            return false;
        }
        boolean hasBusinessTimeline = draft.timeline() != null && draft.timeline().stream()
                .anyMatch(step -> step != null && !step.isTransit() && step.poiId() != null && !step.poiId().isBlank());
        return !hasBusinessTimeline;
    }

    private boolean hasConcretePlanningWindow(PlanIntent intent) {
        return intent != null
                && intent.startTime() != null && !intent.startTime().isBlank()
                && intent.endTime() != null && !intent.endTime().isBlank()
                && intent.totalMinutes() > 0
                && intent.headcount() > 0;
    }

    private void emitConsultationPatchDeferral(ContextPack context, PlanPatch patch, Consumer<SseEvent> emitter) {
        emitTool(emitter, "OBSERVATION", 2, context,
                "plan.edit deferred: consultation draft has no timeline yet");
        String candidateName = extractCandidateName(context, patch);
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        if (context != null && draft != null) {
            PendingAction pending = candidateName != null
                    ? pendingForDeferredPatch(context, patch, candidateName)
                    : new PendingAction("ASK_CONTEXT", null, null,
                    List.of("time", "location", "headcount", "build plan"),
                    "CONTEXTUAL_RESEARCH", patch, null,
                    List.of("timeWindow", "locationScope", "headcount"), baseSlotsFromContext(context), true);
            sessionStateStore.savePending(draft.planId(), draft.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Need concrete planning window before applying candidate: " + candidateName, Instant.now()));
            SlotCollectionService.SlotCollectionPrompt slotPrompt =
                    slotCollectionService.forPending(draft.planId(), pending);
            String message = candidateName == null
                    ? slotPrompt.message()
                    : "我先记住「" + candidateName + "」。" + slotPrompt.message();
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, draft.planId(), draft.intent(), draft.orderIntents(),
                    "PENDING_CONFIRMATION", pending.selectedPatch(), slotPrompt.card()));
            return;
        }
        if (candidateName != null) {
            PendingAction pending = pendingForDeferredPatch(context, patch, candidateName);
            sessionStateStore.savePending(draft.planId(), draft.userId(),
                    pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Need concrete planning window before applying candidate: " + candidateName, Instant.now()));
            String message = "我先记住你选的「" + candidateName
                    + "」。现在还差可执行的时间、地点/范围和人数信息；你可以补充一个时间段、活动范围和人数，我会接着当前选择继续排。";
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, draft.planId(), draft.intent(), draft.orderIntents(),
                    "PENDING_CONFIRMATION"));
            return;
        }
        sessionStateStore.savePending(draft.planId(), draft.userId(),
                new PendingAction("ASK_CONTEXT", null, null, List.of("time", "location", "headcount", "build plan")),
                new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                        "Need concrete planning window before applying candidate: " + candidateName, Instant.now()));
        String selected = selectedPoiHint(patch)
                .map(value -> "我先记住你对这个候选感兴趣。")
                .orElse("这个操作需要先确认偏好和出行信息。");
        String message = selected + " 现在还没有时间、地点和人数约束，不能直接放进拼图。你可以先选一个偏好方向，或者告诉我大概什么时候、在哪个区域见。";
        message = (selectedPoiHint(patch).isPresent() ? "我先记住你对这个候选感兴趣。" : "这个操作需要先确认出行信息。")
                + " 现在还没有具体的出发时间、结束时间和人数，不能直接放进拼图。你可以告诉我比如“下午两点开始，两个人，玩三小时”。";
        emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                null, draft.planId(), draft.intent(), draft.orderIntents(),
                "PENDING_CONFIRMATION"));
    }

    private PendingAction pendingForDeferredPatch(ContextPack context, PlanPatch patch, String candidateName) {
        Map<String, Object> slots = baseSlotsFromContext(context);
        boolean movie = selectedMetadata(patch, "MOVIE_TITLE:").isPresent()
                || selectedMetadata(patch, "MOVIE_ID:").isPresent();
        String phase = patch == null || patch.target() == null ? "" :
                firstNonBlank(patch.target().phase(), patch.target().activityType());
        boolean dining = "DINING".equalsIgnoreCase(phase) || "RESTAURANT".equalsIgnoreCase(phase);
        String type = movie ? "MOVIE_SCHEDULING" : (dining ? "PLAN_SLOT_FILLING" : "ASK_CONTEXT");
        String workflowType = movie ? "MOVIE" : (dining ? "DINING_LOCKED_PLAN" : "CONTEXTUAL_RESEARCH");
        List<String> requiredSlots = movie
                ? List.of("timeWindow", "locationScope", "headcount")
                : List.of("startTime", "duration", "locationScope", "headcount", "orderPreference");
        return new PendingAction(type, null, null, List.of("time", "location", "headcount", "build plan"),
                workflowType, patch, candidateName, requiredSlots, slots, true);
    }

    private Map<String, Object> baseSlotsFromContext(ContextPack context) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        if (draft == null || draft.intent() == null) return Map.of();
        return fallbackSlotExtractor.explicitSlotsFromIntent(draft.intent());
    }

    private String extractCandidateName(ContextPack context, PlanPatch patch) {
        Optional<String> movieTitle = selectedMetadata(patch, "MOVIE_TITLE:");
        return movieTitle.orElseGet(() -> {
            Optional<String> selectedPoiIdOpt = selectedPoiHint(patch).map(val -> val.substring("SELECTED_POI:".length()));
            if (selectedPoiIdOpt.isPresent()) {
                String selectedPoiId = selectedPoiIdOpt.get();
                for (CandidateSet set : context.activeCandidates()) {
                    for (CandidateItem item : set.items()) {
                        if (item.poi().poiId().equals(selectedPoiId)) {
                            return item.poi().name();
                        }
                    }
                }
            }
            return "candidate";
        });
    }

    private Optional<String> selectedMetadata(PlanPatch patch, String prefix) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return Optional.empty();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()))
                .findFirst();
    }

    private Optional<String> selectedPoiHint(PlanPatch patch) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) return Optional.empty();
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith("SELECTED_POI:"))
                .findFirst();
    }

    private void applySelectedCandidate(ContextPack context, AgentCommand command, Consumer<SseEvent> emitter) {
        SessionState state = getSessionState(context);
        PendingAction pending = state.pendingAction();
        String candidateSetId = command.candidateSetId() == null && pending != null
                ? pending.candidateSetId()
                : command.candidateSetId();
        emitTool(emitter, "ACTION", 2, context, "candidate.select: resolve pending candidate");
        CandidateSet candidateSet = context.activeCandidates().stream()
                .filter(set -> set.candidateSetId().equals(candidateSetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate set not found: " + candidateSetId));
        int selectedIndex = command.selectedIndex() == null ? 1 : command.selectedIndex();
        CandidateItem item = candidateSet.items().stream()
                .filter(candidate -> candidate.index() == selectedIndex)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Candidate index not found: " + selectedIndex));
        emitTool(emitter, "OBSERVATION", 2, context, "candidate.select result: index=" + selectedIndex
                + ", poiId=" + item.poi().poiId() + ", name=" + item.poi().name());
        if (shouldDeferPatchInConsultation(context, item.planPatch())) {
            emitConsultationPatchDeferral(context, item.planPatch(), emitter);
            return;
        }
        sessionStateStore.clearPending(context.planId(),
                new RecentEvent(RecentEventType.CANDIDATE_SELECTED, item.poi().name(), Instant.now()));
        applyDeltaAndMaybeRecommend(context, PlanDelta.fromPatch(item.planPatch()), item.planPatch(), emitter);
    }

    private void offerReplacementCandidates(ContextPack context, AgentCommand command, Consumer<SseEvent> emitter) {
        emitCandidateCard(context, patchFactory.replacementFromCommand(context, command), emitter);
    }

    private void extendPlanEndTime(ContextPack context, AgentCommand command, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        String newEndTime = String.valueOf(command.slots().getOrDefault("newEndTime", draft.intent().endTime()));
        applyDeltaAndMaybeRecommend(context,
                patchFactory.editEndTime(draft.intent().startTime(), newEndTime),
                patchFactory.keepAndReplan(),
                emitter);
    }

    public void cancelPendingAction(ContextPack context, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        sessionStateStore.clearPending(context.planId(),
                new RecentEvent(RecentEventType.PENDING_CANCELLED, context.userTurn(), Instant.now()));
        emitter.accept(new SseEvent("FINISH", 1, textService.pendingCancelled(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION"));
    }

    private void emitClarification(ContextPack context, AgentCommand command, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        emitter.accept(new SseEvent("FINISH", 1,
                command.clarificationQuestion() == null ? textService.clarificationFallback() : command.clarificationQuestion(),
                context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                context.planId(), draft.intent(), draft.orderIntents(),
                "PENDING_CONFIRMATION"));
    }

    private void applyDeltaAndMaybeRecommend(ContextPack context,
                                             PlanDelta delta,
                                             PlanPatch patch,
                                             Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        if (planEditorEngine == null) {
            emitClarification(context, new AgentCommand("MODIFY_PLAN", 0.5, context.selectedSegmentId(), null, null,
                    Map.of(), null, "CLARIFY", RouteMode.FAST_WORKFLOW, true,
                    textService.clarificationFallback(), null), emitter);
            return;
        }
        emitter.accept(new SseEvent("START", 0, textService.fastWorkflowStarted(), context.draft().timeline(),
                null, null, null, null, context.planId(), draft.intent(),
                draft.orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("ACTION", 3, "plan.edit: " + patch.editType(),
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, null));

        PlanResponse response = planEditorEngine.applyDelta(draft, delta);
        PlanExecutionStore.DraftPlan updatedDraft = executionStore.find(response.planId()).orElse(draft);
        sessionStateStore.syncDraft(updatedDraft);
        emitter.accept(new SseEvent("OBSERVATION", 3, "plan.edit result: timelineSteps=" + response.timeline().size()
                + ", status=" + response.status(), response.timeline(), response.status(), response.orderGroupId(),
                response.notificationText(), response.degradationNote(), response.planId(), response.intent(),
                response.orderIntents(), response.executionStatus(), patch, null));

        ActionCard candidateCard = null;
        PlanPatch autoPatch = null;
        if (replacementSearchEngine != null) {
            Optional<PlanStep> leisureStep = findAutoRecommendStep(response.timeline());
            if (leisureStep.isPresent()) {
                PlanStep step = leisureStep.get();
                String phase = inferPhaseForTime(step.startTime());
                emitter.accept(new SseEvent("ACTION", 4, "poi.search.autoRecommendation: phase=" + phase,
                        response.timeline(), response.status(), response.orderGroupId(), response.notificationText(),
                        response.degradationNote(), response.planId(), response.intent(), response.orderIntents(),
                        response.executionStatus(), patch, null));
                autoPatch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                        new PlanPatch.Target(step.segmentId(), null, phase, phase, null, null),
                        new PlanPatch.Requirements(List.of(), patch.requirements().avoid(), patch.requirements().prefer(),
                                null, null, null, false),
                        true);
                CandidateCardResult result = candidateCardService.buildCandidateCard(updatedDraft, autoPatch);
                if (result.card().options() != null && !result.card().options().isEmpty()) {
                    candidateCard = result.card();
                    saveCandidateState(updatedDraft, result.candidateSet(), step.segmentId());
                    emitter.accept(new SseEvent("OBSERVATION", 4, "poi.search.autoRecommendation result: "
                            + result.card().options().size() + " options", response.timeline(), response.status(),
                            response.orderGroupId(), response.notificationText(), response.degradationNote(),
                            response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                            autoPatch, candidateCard));
                }
            }
        }

        PlanPatch responsePatch = autoPatch == null ? patch : autoPatch;
        emitter.accept(new SseEvent("PLAN_STEP", 5, "timeline.update: " + textService.planUpdated(), response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                responsePatch, candidateCard));
        String summary = candidateCard == null ? response.summary() : response.summary() + textService.autoRecommendationSuffix();
        emitter.accept(new SseEvent("FINISH", 3, summary, response.timeline(),
                response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                responsePatch, candidateCard, null, response.conflicts(), response.repairOptions(),
                response.version(), response.planStatus(), response.weather(), summary));
    }

    private void emitCandidateCard(ContextPack context, PlanPatch patch, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        CandidateCardResult result = candidateCardService.buildCandidateCard(draft, patch);
        if (!result.candidateSet().items().isEmpty()) {
            saveCandidateState(draft, result.candidateSet(), result.candidateSet().targetSegmentId());
        }
        emitter.accept(new SseEvent("ACTION", 2, "poi.search.replacement: find candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("OBSERVATION", 2, "poi.search.replacement result: " + result.card().options().size() + " candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
        emitter.accept(new SseEvent("ACTION", 3, "card.render: replacement candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
        emitter.accept(new SseEvent("FINISH", 4, textService.candidatePrompt(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
    }

    private void emitTool(Consumer<SseEvent> emitter, String type, int step, ContextPack context, String content) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        emitter.accept(new SseEvent(type, step, content, context.draft().timeline(), null, null, null,
                null, context.planId(), draft.intent(), draft.orderIntents(),
                "PENDING_CONFIRMATION"));
    }

    private void saveCandidateState(PlanExecutionStore.DraftPlan draft, CandidateSet candidateSet, String targetSegmentId) {
        sessionStateStore.saveCandidates(draft.planId(), draft.userId(), candidateSet,
                new PendingAction("SELECT_CANDIDATE", candidateSet.candidateSetId(), targetSegmentId,
                        List.of("choose index", "more options", "cancel"), workflowTypeFor(candidateSet),
                        null, null, List.of("selection"), Map.of(), true),
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Recommended " + candidateSet.items().size() + " candidates", Instant.now()));
    }

    private String workflowTypeFor(CandidateSet candidateSet) {
        if (candidateSet == null || candidateSet.type() == null) return "CONTEXTUAL_RESEARCH";
        if ("MOVIE".equalsIgnoreCase(candidateSet.type())) return "MOVIE";
        if ("DINING".equalsIgnoreCase(candidateSet.type())) return "DINING_LOCKED_PLAN";
        return "CONTEXTUAL_RESEARCH";
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

    private Optional<PlanPatch> parsePatchPayload(String patchPayload, String interactionSource) {
        if (patchPayload == null || patchPayload.isBlank()) return Optional.empty();
        String trimmed = patchPayload.trim();
        boolean explicitPatch = interactionSource != null
                && interactionSource.toUpperCase(Locale.ROOT).contains("SUBMIT_PATCH");
        if (!trimmed.startsWith("{") && !explicitPatch) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(trimmed, PlanPatch.class));
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

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private record PlanChoiceSpec(
            String title,
            String description,
            List<String> activityTags,
            List<String> diningTags
    ) {}
}
