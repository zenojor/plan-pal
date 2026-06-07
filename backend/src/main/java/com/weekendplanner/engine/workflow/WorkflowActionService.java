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
import com.fasterxml.jackson.databind.JsonNode;
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

import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private final CandidateChainWorkflow candidateChainWorkflow;

    private final InteractionRouter interactionRouter;
    private final ConversationalQaService conversationalQaService;
    private final PendingSlotFiller pendingSlotFiller = new PendingSlotFiller();
    private final FallbackSlotExtractor fallbackSlotExtractor = new FallbackSlotExtractor();
    private final ChatModel planChoiceChatModel;

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
                                ConversationalQaService conversationalQaService,
                                ObjectProvider<ChatModel> chatModelProvider) {
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
        this.planChoiceChatModel = chatModelProvider == null ? null : chatModelProvider.getIfAvailable();
        this.slotCollectionService = new SlotCollectionService();
        this.candidateChainWorkflow = new CandidateChainWorkflow(
                executionStore,
                sessionStateStore,
                this.planEditorEngine,
                this.candidateCardService,
                replacementSearchEngine,
                this.runtime);
    }

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
        this(fastPlanEngine, executionStore, intentExtractor, planPatchExtractor, planDeltaExtractor,
                planEditorEngine, replacementSearchEngine, contextAssembler, agentRouter, sessionStateStore,
                objectMapper, runtime, candidateCardService, patchFactory, textService, initialRequestRouter,
                researchRenderWorkflow, consultationWorkflow, interactionRouter, conversationalQaService, null);
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
                ? fastPlanEngine.executePlanStreaming(request, emitter, null, true)
                : fastPlanEngine.executePlan(request, true);
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
        if (isCandidateRefreshPrompt(prompt)) {
            return refreshPendingCandidates(context, prompt, emitter);
        }
        if (decision == null || decision.command() != InteractionCommand.CONTINUE_WORKFLOW) return false;
        PendingAction pending = context.pendingAction();
        if (directPatch == null && isPendingType(pending, "PLAN_CHOICE")) {
            Optional<Integer> choiceIndex = parsePlanChoiceIndex(prompt);
            if (choiceIndex.isEmpty()) {
                choiceIndex = parsePlanChoiceIndex(interactionSource);
            }
            if (choiceIndex.isEmpty()) {
                choiceIndex = parsePlanChoiceIndex(context.userTurn());
            }
            if (choiceIndex.isEmpty()) {
                return false;
            }
            continuePlanChoice(context, interactionSource, prompt, decision.pendingSlotPatch(), emitter);
            return true;
        }
        if (directPatch == null && isPendingType(pending, "INITIAL_PLAN_SLOT_FILLING")) {
            continueInitialPlanSlotFilling(context, decision.pendingSlotPatch(), emitter);
            return true;
        }
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
            consultationWorkflow.continueAfterContext(context, decision.pendingSlotPatch(), emitter);
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
                ? agentRouter.route(context, decision == null ? null : decision.understanding())
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

    private boolean isCandidateRefreshPrompt(String prompt) {
        return prompt != null && (prompt.contains("[REFRESH_CANDIDATES]") || prompt.contains("[REFINE_CANDIDATES]"));
    }

    private boolean refreshPendingCandidates(ContextPack context, String prompt, Consumer<SseEvent> emitter) {
        if (context == null || researchRenderWorkflow == null) return false;
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        SessionState state = getSessionState(context);
        String requestedType = normalizeCandidateType(markerValue(prompt, "cardKind").orElse(null));
        String type = firstNonBlank(requestedType, latestCandidateType(state), pendingWorkflowType(state.pendingAction()));
        if (type == null || type.isBlank()) type = "POI";
        Set<String> excludedIds = excludedCandidateIds(prompt);
        String refineText = refineText(prompt);

        emitter.accept(new SseEvent("ACTION", 2,
                (prompt.contains("[REFINE_CANDIDATES]") ? "candidate.refine" : "candidate.refresh")
                        + ": type=" + type + ", excluded=" + excludedIds.size(),
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", null, null, null,
                List.of(), List.of(), draft.version(), draft.status(), null));

        Optional<CandidateCardResult> refreshed = researchRenderWorkflow.refreshCard(draft, type, refineText, excludedIds);
        if (refreshed.isEmpty() || refreshed.get().card().options().isEmpty()) {
            emitter.accept(new SseEvent("FINISH", 3,
                    "这轮没有找到更合适的新候选，可以换个描述要求再试一次。",
                    context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                    context.planId(), draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                    null, null, null, List.of(), List.of(), draft.version(), draft.status(), null));
            return true;
        }

        CandidateCardResult result = refreshed.get();
        PendingAction previous = state.pendingAction();
        PendingAction pending = new PendingAction("SELECT_CANDIDATE",
                result.candidateSet().candidateSetId(),
                result.candidateSet().targetSegmentId(),
                List.of("choose index", "more options", "describe requirements", "cancel"),
                workflowTypeFor(result.candidateSet()),
                previous == null ? null : previous.selectedPatch(),
                previous == null ? null : previous.selectedLabel(),
                previous == null ? List.of("selection") : previous.requiredSlots(),
                previous == null ? Map.of() : previous.collectedSlots(),
                true);
        sessionStateStore.saveCandidates(context.planId(), context.userId(), result.candidateSet(), pending,
                new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                        "Candidate refresh: " + type + " options=" + result.card().options().size(), Instant.now()));

        emitter.accept(new SseEvent("OBSERVATION", 2,
                "candidate.score: options=" + result.card().options().size(),
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", null, result.card(), null,
                List.of(), List.of(), draft.version(), draft.status(), null));
        emitter.accept(new SseEvent("OBSERVATION", 3,
                "candidate.decision: top=" + result.card().options().get(0).label()
                        + ", score=" + result.card().options().get(0).score(),
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", null, result.card(), null,
                List.of(), List.of(), draft.version(), draft.status(), null));
        emitter.accept(new SseEvent("FINISH", 4,
                prompt.contains("[REFINE_CANDIDATES]") ? "我按你的描述重新筛了一批候选。" : "已换一批新的推荐候选。",
                context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                context.planId(), draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                null, result.card(), null, List.of(), List.of(), draft.version(), draft.status(), null));
        return true;
    }

    private String latestCandidateType(SessionState state) {
        if (state == null || state.lastCandidates().isEmpty()) return null;
        return state.lastCandidates().get(state.lastCandidates().size() - 1).type();
    }

    private String pendingWorkflowType(PendingAction pending) {
        if (pending == null) return null;
        if ("MOVIE".equalsIgnoreCase(pending.workflowType())) return "MOVIE";
        if ("DINING_LOCKED_PLAN".equalsIgnoreCase(pending.workflowType())) return "DINING";
        return pending.workflowType();
    }

    private String normalizeCandidateType(String rawType) {
        if (rawType == null || rawType.isBlank()) return rawType;
        String type = rawType.trim();
        if ("MOVIE_SCREENING".equalsIgnoreCase(type)) return "MOVIE";
        if ("PRODUCT_RESEARCH".equalsIgnoreCase(type)) return "PRODUCT";
        return type;
    }

    private Set<String> excludedCandidateIds(String prompt) {
        Set<String> values = new LinkedHashSet<>();
        markerValue(prompt, "excludePoiIds").ifPresent(value -> addDelimited(values, value));
        markerValue(prompt, "excludeOptionIds").ifPresent(value -> addDelimited(values, value));
        markerValue(prompt, "excludeScreeningIds").ifPresent(value -> addDelimited(values, value));
        return values;
    }

    private void addDelimited(Set<String> target, String value) {
        if (value == null || value.isBlank()) return;
        for (String item : value.split("[,，、\\s]+")) {
            String trimmed = item.trim();
            if (!trimmed.isBlank()) target.add(trimmed);
        }
    }

    private Optional<String> markerValue(String prompt, String key) {
        if (prompt == null || key == null) return Optional.empty();
        Matcher matcher = Pattern.compile(key + "=([^\\s;]+)").matcher(prompt);
        if (!matcher.find()) return Optional.empty();
        String value = matcher.group(1).trim();
        return value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private String refineText(String prompt) {
        if (prompt == null) return "";
        int marker = prompt.indexOf("[REFINE_CANDIDATES]");
        if (marker < 0) return prompt;
        return prompt.substring(marker + "[REFINE_CANDIDATES]".length()).trim();
    }

    private void continuePlanChoice(ContextPack context,
                                    String interactionSource,
                                    String prompt,
                                    PendingSlotPatch slotPatch,
                                    Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        PendingAction pending = getSessionState(context).pendingAction();
        if (slotPatch != null && slotPatch.hasSlots() && pending != null) {
            pending = pending.mergeCollectedSlots(slotPatch.slots());
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            slotPatch.reason().isBlank() ? "Plan choice slots updated" : slotPatch.reason(), Instant.now()));
        }
        Optional<Integer> choiceIndex = parsePlanChoiceIndex(prompt);
        if (choiceIndex.isEmpty()) {
            choiceIndex = parsePlanChoiceIndex(interactionSource);
        }
        if (choiceIndex.isEmpty()) {
            choiceIndex = parsePlanChoiceIndex(context.userTurn());
        }
        if (choiceIndex.isEmpty()) {
            choiceIndex = selectedPlanChoiceIndex(pending);
        }
        if (choiceIndex.isEmpty()) {
            PendingAction kept = pending == null ? null : pending;
            if (kept != null) {
                sessionStateStore.savePending(context.planId(), context.userId(), kept,
                        new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                                "Plan choice still waiting for selected option", Instant.now()));
            }
            emitter.accept(new SseEvent("FINISH", 2,
                    "先选一个方案方向，我再把对应地点放进拼图。",
                    context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                    context.planId(), draft.intent(), draft.orderIntents(), "OPTIONS_READY",
                    null, planChoiceCardFromPending(context.planId(), pending)));
            return;
        }

        int index = choiceIndex.get();
        List<String> poiIds = planChoicePoiIds(pending, index);
        if (poiIds.isEmpty()) {
            emitter.accept(new SseEvent("FINISH", 2,
                    "这个方案的地点信息已过期，请重新发起规划后再选择。",
                    context.draft().timeline(), "SUCCESS", "", context.draft().notificationText(), null,
                    context.planId(), draft.intent(), draft.orderIntents(), "OPTIONS_READY",
                    null, planChoiceCardFromPending(context.planId(), pending)));
            return;
        }

        List<String> missingSlots = missingCriticalSlots(draft.intent(), pending);
        if (!missingSlots.isEmpty()) {
            PendingAction updated = pending == null ? null : pending.mergeCollectedSlots(Map.of(
                    "selectedChoiceIndex", index,
                    "selectedChoicePoiIds", poiIds));
            emitPendingQuestion(context, updated,
                    "我先记住你选的方案 " + index + "。还需要补充出行时间/人数后，才能把它放进可执行拼图。",
                    missingSlots, emitter);
            return;
        }

        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: plan_choice index=" + index,
                context.draft().timeline(), null, null, null, null, context.planId(), draft.intent(),
                draft.orderIntents(), "PENDING_CONFIRMATION"));
        String selectedPrompt = buildSelectedPlanPrompt(draft.intent(), poiIds, prompt);
        PlanResponse response = fastPlanEngine.executePlanStreaming(
                new PlanRequest(context.userId(), selectedPrompt, context.planId()), emitter);
        if (response.timeline() != null && !response.timeline().isEmpty()) {
            rememberDraft(response.planId());
            sessionStateStore.clearPending(context.planId(),
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Plan choice completed: " + index, Instant.now()));
        } else if (pending != null) {
            sessionStateStore.savePending(context.planId(), context.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Plan choice kept after empty build", Instant.now()));
        }
    }

    private List<String> missingCriticalSlots(PlanIntent intent, PendingAction pending) {
        if (intent == null || !intent.isMissingCriticalPlanningInfo()) return List.of();
        List<String> missing = new ArrayList<>();
        String original = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
        boolean hasTimeSignal = containsAny(original, "点", "分", "时", "am", "pm", "clock", "：", ":",
                "下午", "晚上", "中午", "上午", "早上", "夜里", "凌晨", "time=", "starttime=",
                "timerange=", "timewindow=", "[plan_details]");
        boolean hasHeadcountSignal = containsAny(original, "人", "位", "独自", "自己", "情侣", "老婆", "老公",
                "妻子", "丈夫", "孩子", "娃", "朋友", "聚会", "聚聚", "战友", "闺蜜", "同学", "同事", "团建", "约会",
                "headcount=", "总共", "一共");
        if (!hasCollectedPlanningTime(pending)
                && (intent.startTime() == null || intent.startTime().isBlank() || ("14:00".equals(intent.startTime()) && !hasTimeSignal))) {
            missing.add("TIME_RANGE");
        }
        if (!hasCollectedHeadcount(pending)
                && (intent.headcount() <= 0 || (intent.headcount() == 1 && !hasHeadcountSignal))) {
            missing.add("HEADCOUNT");
        }
        return missing;
    }

    private boolean hasCollectedPlanningTime(PendingAction pending) {
        if (pending == null || pending.collectedSlots() == null || pending.collectedSlots().isEmpty()) return false;
        return hasNonBlankSlot(pending, "startTime")
                || hasNonBlankSlot(pending, "endTime")
                || hasNonBlankSlot(pending, "timeRange")
                || hasNonBlankSlot(pending, "timeWindow")
                || hasNonBlankSlot(pending, "maxEndTime");
    }

    private boolean hasCollectedHeadcount(PendingAction pending) {
        if (pending == null || pending.collectedSlots() == null || pending.collectedSlots().isEmpty()) return false;
        Object value = pending.collectedSlots().get("headcount");
        if (value instanceof Number number) return number.intValue() > 0;
        return value != null && !String.valueOf(value).isBlank();
    }

    private boolean hasNonBlankSlot(PendingAction pending, String key) {
        Object value = pending == null || pending.collectedSlots() == null ? null : pending.collectedSlots().get(key);
        return value != null && (!(value instanceof String text) || !text.isBlank());
    }

    private String buildSelectedPlanPrompt(PlanIntent intent, List<String> poiIds, String adjustmentText) {
        String original = intent == null || intent.originalPrompt() == null || intent.originalPrompt().isBlank()
                ? "用户选择了一条推荐路线"
                : intent.originalPrompt();
        StringBuilder builder = new StringBuilder();
        builder.append("[BUILD_SELECTED_PLAN] 原始需求：").append(original)
                .append("。基于推荐的商家（商户ID: ").append(String.join("、", poiIds)).append("）生成行程拼图");
        if (intent != null && intent.headcount() > 0) {
            builder.append("，总共 ").append(intent.headcount()).append(" 个人");
        }
        if (adjustmentText != null && !adjustmentText.isBlank() && !adjustmentText.toUpperCase(Locale.ROOT).contains("BUILD_PLAN:CHOICE")) {
            builder.append("，并且特殊要求：").append(adjustmentText.trim());
        }
        builder.append("。请保留原始需求中的时间、同行人、距离和节奏约束；如果原始需求没有明确时间范围，再用一句话追问时间，不要填入默认时间段。");
        return builder.toString();
    }

    private Optional<Integer> parsePlanChoiceIndex(String text) {
        if (text == null || text.isBlank()) return Optional.empty();
        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher buildMarker = Pattern.compile("build_plan:choice[-:]?(\\d+)").matcher(normalized);
        if (buildMarker.find()) return Optional.of(Integer.parseInt(buildMarker.group(1)));
        Matcher clientAction = Pattern.compile("plan-choice-[^\\s]+-(\\d+)").matcher(normalized);
        if (clientAction.find()) return Optional.of(Integer.parseInt(clientAction.group(1)));
        Matcher choiceMarker = Pattern.compile("choice[-:：]?(\\d+)").matcher(normalized);
        if (choiceMarker.find()) return Optional.of(Integer.parseInt(choiceMarker.group(1)));
        if (normalized.contains("方案一") || normalized.contains("第一个") || normalized.contains("第一")
                || normalized.contains("选一") || normalized.contains("first")) return Optional.of(1);
        if (normalized.contains("方案二") || normalized.contains("第二个") || normalized.contains("第二")
                || normalized.contains("选二") || normalized.contains("second")) return Optional.of(2);
        if (normalized.contains("方案三") || normalized.contains("第三个") || normalized.contains("第三")
                || normalized.contains("选三") || normalized.contains("third")) return Optional.of(3);
        Matcher digit = Pattern.compile("(^|\\D)([123])(\\D|$)").matcher(normalized);
        if (digit.find()) return Optional.of(Integer.parseInt(digit.group(2)));
        return Optional.empty();
    }

    private Optional<Integer> selectedPlanChoiceIndex(PendingAction pending) {
        if (pending == null || pending.collectedSlots() == null) return Optional.empty();
        Object value = pending.collectedSlots().get("selectedChoiceIndex");
        if (value instanceof Number number) {
            int index = number.intValue();
            return index >= 1 && index <= 3 ? Optional.of(index) : Optional.empty();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                int index = Integer.parseInt(text.trim());
                return index >= 1 && index <= 3 ? Optional.of(index) : Optional.empty();
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private List<String> planChoicePoiIds(PendingAction pending, int index) {
        if (pending == null || pending.collectedSlots() == null) return List.of();
        Object value = pending.collectedSlots().get("choice." + index + ".poiIds");
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).filter(item -> !item.isBlank()).toList();
        }
        if (value instanceof String text && !text.isBlank()) {
            return java.util.Arrays.stream(text.split("[,，、]"))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .toList();
        }
        return List.of();
    }

    private ActionCard planChoiceCardFromPending(String planId, PendingAction pending) {
        if (pending == null || pending.collectedSlots() == null) return null;
        List<ActionCard.ActionOption> options = new ArrayList<>();
        for (int index = 1; index <= 3; index++) {
            List<String> poiIds = planChoicePoiIds(pending, index);
            if (poiIds.isEmpty()) continue;
            String label = stringSlot(pending, "choice." + index + ".label", "方案 " + index);
            String description = stringSlot(pending, "choice." + index + ".description", "继续构建这个方案。");
            String id = stringSlot(pending, "choice." + index + ".id", "plan-choice-" + planId + "-" + index);
            String optionPrompt = stringSlot(pending, "choice." + index + ".prompt", "BUILD_PLAN:choice-" + index);
            options.add(new ActionCard.ActionOption(id, label, description, "BUILD_PLAN", null,
                    optionPrompt, null, poiIds, null, "PLAN_CHOICE"));
        }
        if (options.isEmpty()) return null;
        return new ActionCard("plan-choice-" + planId, "选择一个方案来构建计划",
                "先选方向，我再把相应地点放进拼图并生成可执行时间线。",
                options, "也可以补充你想调整的方向，比如更室内、少排队、先吃饭", true, "PLAN_CHOICE");
    }

    private String stringSlot(PendingAction pending, String key, String fallback) {
        Object value = pending == null || pending.collectedSlots() == null ? null : pending.collectedSlots().get(key);
        String text = value == null ? "" : String.valueOf(value);
        return text.isBlank() ? fallback : text;
    }

    private void continueInitialPlanSlotFilling(ContextPack context,
                                                PendingSlotPatch slotPatch,
                                                Consumer<SseEvent> emitter) {
        PendingAction pending = mergePendingSlots(context, "INITIAL_PLAN_SLOT_FILLING", slotPatch);
        if (pending == null) {
            return;
        }
        List<String> missingSlots = slotCollectionService.missingSlots(pending);
        if (!missingSlots.isEmpty()) {
            emitPendingQuestion(context, pending, null, missingSlots, emitter);
            return;
        }

        PlanExecutionStore.DraftPlan draft = getDraft(context);
        String originalPrompt = stringSlot(pending, "originalPrompt",
                draft.intent() == null ? context.userTurn() : draft.intent().originalPrompt());
        String completedPrompt = buildCompletedInitialPrompt(originalPrompt, pending.collectedSlots());
        PlanIntent completedIntent = completedInitialIntent(draft.intent(), completedPrompt, pending.collectedSlots());

        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: initial_plan_slot_filling",
                context.draft().timeline(), null, null, null, null, context.planId(),
                completedIntent, draft.orderIntents(), "PENDING_CONFIRMATION"));
        createPlanChoiceDraft(new PlanRequest(context.userId(), completedPrompt, context.planId()),
                null, emitter, pending.collectedSlots(), completedIntent);
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
                response.version(), response.planStatus(), response.weather(), response.summary(), List.of()));
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
        TurnUnderstanding routeUnderstanding = route == null ? null : route.understanding();
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt(), routeUnderstanding);
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
        boolean missingTime = route == null || route.evidence() == null || !route.evidence().timeSignal();
        boolean missingHeadcount = route == null || route.evidence() == null || !route.evidence().headcountSignal();
        PendingAction pending = initialPlanSlotPending(request.prompt(), extracted, missingTime, missingHeadcount);
        sessionStateStore.savePending(planId, request.userId(), pending,
                new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                        "Initial plan slot collection started", Instant.now()));
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

    private PendingAction initialPlanSlotPending(String originalPrompt,
                                                 PlanIntent extracted,
                                                 boolean missingTime,
                                                 boolean missingHeadcount) {
        List<String> requiredSlots = new ArrayList<>();
        if (missingTime) requiredSlots.add("TIME_RANGE");
        if (missingHeadcount) requiredSlots.add("HEADCOUNT");
        if (requiredSlots.isEmpty()) {
            requiredSlots.add("TIME_RANGE");
            requiredSlots.add("HEADCOUNT");
        }
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("originalPrompt", originalPrompt == null ? "" : originalPrompt);
        slots.putAll(fallbackSlotExtractor.explicitSlotsFromIntent(extracted));
        return new PendingAction("INITIAL_PLAN_SLOT_FILLING", null, null,
                List.of("time", "headcount", "build plan", "cancel"),
                "INITIAL_PLAN", null, null, requiredSlots, slots, true);
    }

    public PlanResponse createPlanChoiceDraft(PlanRequest request,
                                               InitialRouteCommand route,
                                               Consumer<SseEvent> emitter) {
        return createPlanChoiceDraft(request, route, emitter, Map.of(), null);
    }

    private PlanResponse createPlanChoiceDraft(PlanRequest request,
                                               InitialRouteCommand route,
                                               Consumer<SseEvent> emitter,
                                               Map<String, Object> collectedSlots,
                                               PlanIntent forcedIntent) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        TurnUnderstanding routeUnderstanding = route == null ? null : route.understanding();
        PlanIntent extracted = forcedIntent != null ? forcedIntent
                : intentExtractor == null ? null : intentExtractor.extract(request.prompt(), routeUnderstanding);
        PlanIntent intent = forcedIntent != null ? forcedIntent : extracted == null
                ? new PlanIntent(1, List.of(), null, null, 0, null,
                List.of(), List.of(), null, null, request.prompt(), false)
                : new PlanIntent(extracted.headcount(), extracted.participants(), extracted.startTime(),
                extracted.endTime(), extracted.totalMinutes(), extracted.sceneType(),
                extracted.requestedSegments(), extracted.dietaryConstraints(), extracted.drinkPreference(),
                extracted.locationScope(), request.prompt(), extracted.pace(), extracted.budgetLevel(),
                extracted.hasChildren(), extracted.childAge(), extracted.preferredTransportMode(),
                extracted.avoid(), extracted.mustHave(), extracted.weatherSensitive(), false);

        PlanChoiceCardResult cardResult = planChoiceCard(planId, intent);
        ActionCard card = cardResult.card();
        String message = "我先给你 3 个方向，选一个后再把对应地点放进拼图。";
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), message);
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);
        PendingAction pending = mergePlanChoiceCollectedSlots(planChoicePending(card, request.prompt()), collectedSlots);
        sessionStateStore.savePending(planId, request.userId(), pending,
                new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                        "Plan choice options ready", Instant.now()));

        emitter.accept(new SseEvent("START", 0, "plan.options: prepare route choices", List.of(),
                null, null, null, null, planId, intent, List.of(), "OPTIONS_READY", null, card));
        emitter.accept(new SseEvent("OBSERVATION", 0, "plan.options.source: " + cardResult.source(), List.of(),
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
        return true;
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

    private PlanChoiceCardResult planChoiceCard(String planId, PlanIntent intent) {
        PlanChoiceSpecResult specResult = planChoiceSpecs(intent);
        List<PlanChoiceSpec> specs = specResult.specs();
        Set<String> used = new LinkedHashSet<>();
        List<ActionCard.ActionOption> options = new ArrayList<>();
        for (int i = 0; i < specs.size() && options.size() < 3; i++) {
            ActionCard.ActionOption option = planChoiceOption(planId, i + 1, specs.get(i), intent, used);
            if (option.poiIds() != null && !option.poiIds().isEmpty()) {
                options.add(option);
            }
        }
        ActionCard card = new ActionCard("plan-choice-" + planId, "选择一个方案来构建计划",
                "先选方向，我再把相应地点放进拼图并生成可执行时间线。",
                options, "也可以补充你想调整的方向，比如更室内、少排队、先吃饭",
                true, "PLAN_CHOICE");
        return new PlanChoiceCardResult(card, specResult.source());
    }

    private PendingAction planChoicePending(ActionCard card, String originalPrompt) {
        Map<String, Object> slots = new LinkedHashMap<>();
        slots.put("originalPrompt", originalPrompt == null ? "" : originalPrompt);
        List<ActionCard.ActionOption> options = card == null ? List.of() : card.options();
        for (int i = 0; i < options.size(); i++) {
            ActionCard.ActionOption option = options.get(i);
            int index = i + 1;
            slots.put("choice." + index + ".id", option.id());
            slots.put("choice." + index + ".label", option.label());
            slots.put("choice." + index + ".description", option.description());
            slots.put("choice." + index + ".prompt", option.prompt());
            slots.put("choice." + index + ".poiIds", option.poiIds() == null ? List.of() : option.poiIds());
        }
        return new PendingAction("PLAN_CHOICE", null, null,
                List.of("choose plan option", "ask question", "customize", "cancel"),
                "PLAN_CHOICE", null, null, List.of("choice"), slots, true);
    }

    private PlanChoiceSpecResult planChoiceSpecs(PlanIntent intent) {
        PlanChoiceContext context = planChoiceContext(intent);
        Optional<List<PlanChoiceSpec>> llmSpecs = llmPlanChoiceSpecs(intent, context);
        if (llmSpecs.isPresent()) return new PlanChoiceSpecResult(llmSpecs.get(), "LLM");
        List<String> phases = planChoicePhases(intent, context);
        return new PlanChoiceSpecResult(List.of(
                new PlanChoiceSpec(context.titlePrefix() + (context.wantsMovie() ? "电影少折腾串联" : "少折腾串联"),
                        "按" + context.scopeText() + "少绕路串起来，优先" + context.constraintText()
                                + "，适合先" + (context.wantsMovie() ? "看电影" : "活动") + "再吃喝。",
                        planChoiceSegments(phases, context, "balanced")),
                new PlanChoiceSpec(context.titlePrefix() + (context.wantsMovie() ? "电影后补能" : "室内聊天补能"),
                        "把" + (context.wantsMovie() ? "电影" : "能坐下来聊天的点") + "放前面，餐饮和" + (context.wantsDrinks() ? "小酌" : "收尾")
                                + "接得更顺，同样优先" + context.constraintText() + "。",
                        planChoiceSegments(phases, context, "quiet")),
                new PlanChoiceSpec(context.titlePrefix() + (context.wantsMovie() ? "电影紧凑路线" : "紧凑收尾路线"),
                        "压缩移动和等待，把吃饭、" + (context.wantsMovie() ? "电影" : "轻活动") + (context.wantsDrinks() ? "、喝酒" : "")
                                + "排得更紧凑，继续满足" + context.constraintText() + "。",
                        planChoiceSegments(phases, context, "compact"))
        ), "RULE_FALLBACK");
    }

    private Optional<List<PlanChoiceSpec>> llmPlanChoiceSpecs(PlanIntent intent, PlanChoiceContext context) {
        if (planChoiceChatModel == null || intent == null) return Optional.empty();
        try {
            String content = planChoiceChatModel.call(new Prompt(List.of(
                    new SystemMessage(planChoiceSystemPrompt()),
                    new UserMessage(planChoiceUserPrompt(intent, context))
            ))).getResult().getOutput().getText();
            List<PlanChoiceSpec> specs = parsePlanChoiceSpecs(content, context);
            if (specs.size() == 3) {
                log.info("[PlanChoice] LLM generated 3 plan choices for prompt={}", intent.originalPrompt());
                return Optional.of(specs);
            }
            return Optional.empty();
        } catch (Exception e) {
            log.warn("[PlanChoice] LLM plan choice generation failed, using fallback: {}", e.toString());
            return Optional.empty();
        }
    }

    private String planChoiceSystemPrompt() {
        return """
                You are PlanPal's plan-choice designer. Return compact JSON only, no markdown.
                Create exactly 3 Chinese route directions for the user's request.
                You do not invent POIs. You only choose phases and preference tags for backend search.
                Schema:
                {"choices":[
                  {"title":"短标题，不能泛泛而谈","description":"说明如何满足用户约束",
                   "segments":[
                     {"phase":"LEISURE|DINING|DRINKS|CINEMA","tags":["INDOOR","NEARBY","QUIET","SOCIAL_DINING","BAR","DRINKS","MOVIE","PARK","CHILD_FRIENDLY"]}
                   ]}
                ]}
                Rules:
                - Preserve hard preferences such as movie/cinema, drinks/bar, dining, indoor, nearby, low queue, relaxed/compact.
                - If the user wants movies, include a CINEMA segment in every choice.
                - If the user wants drinks/alcohol/bar, include a DRINKS segment in every choice.
                - If the user wants dining/food, include a DINING segment in every choice.
                - If the user asks for light activity, include LEISURE unless CINEMA is the activity.
                - Use tags only as search preferences, not place names.
                """;
    }

    private String planChoiceUserPrompt(PlanIntent intent, PlanChoiceContext context) {
        return objectMapper.createObjectNode()
                .put("originalPrompt", intent.originalPrompt())
                .put("headcount", intent.headcount())
                .put("startTime", intent.startTime())
                .put("endTime", intent.endTime())
                .put("sceneType", intent.sceneType())
                .put("pace", intent.pace())
                .put("locationScope", intent.locationScope())
                .put("wantsMovie", context.wantsMovie())
                .put("wantsDrinks", context.wantsDrinks())
                .put("indoor", context.indoor())
                .put("lowQueue", context.lowQueue())
                .put("nearby", context.nearby())
                .put("constraintText", context.constraintText())
                .toString();
    }

    private List<PlanChoiceSpec> parsePlanChoiceSpecs(String raw, PlanChoiceContext context) throws IOException {
        if (raw == null || raw.isBlank()) return List.of();
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return List.of();
        JsonNode choices = objectMapper.readTree(raw.substring(start, end + 1)).path("choices");
        if (!choices.isArray()) return List.of();
        List<PlanChoiceSpec> specs = new ArrayList<>();
        for (JsonNode node : choices) {
            if (specs.size() >= 3) break;
            String title = node.path("title").asText("").trim();
            String description = node.path("description").asText("").trim();
            List<PlanChoiceSegment> segments = parsePlanChoiceSegments(node.path("segments"), context);
            if (!title.isBlank() && !description.isBlank() && !segments.isEmpty()) {
                specs.add(new PlanChoiceSpec(title, description, segments));
            }
        }
        return specs;
    }

    private List<PlanChoiceSegment> parsePlanChoiceSegments(JsonNode segmentsNode, PlanChoiceContext context) {
        LinkedHashMap<String, List<String>> byPhase = new LinkedHashMap<>();
        if (segmentsNode != null && segmentsNode.isArray()) {
            for (JsonNode segmentNode : segmentsNode) {
                String phase = normalizePlanChoicePhase(segmentNode.path("phase").asText(""));
                if (!Set.of("LEISURE", "DINING", "DRINKS", "CINEMA").contains(phase)) continue;
                LinkedHashSet<String> tags = new LinkedHashSet<>();
                JsonNode tagsNode = segmentNode.path("tags");
                if (tagsNode.isArray()) {
                    for (JsonNode tagNode : tagsNode) {
                        String tag = tagNode.asText("").trim().toUpperCase(Locale.ROOT);
                        if (!tag.isBlank()) tags.add(tag);
                    }
                }
                tags.addAll(planChoiceTags(phase, context, "balanced"));
                byPhase.put(phase, List.copyOf(tags));
            }
        }
        if (context.wantsMovie() && !byPhase.containsKey("CINEMA")) {
            byPhase.put("CINEMA", planChoiceTags("CINEMA", context, "balanced"));
        }
        if (context.wantsDrinks() && !byPhase.containsKey("DRINKS")) {
            byPhase.put("DRINKS", planChoiceTags("DRINKS", context, "balanced"));
        }
        return byPhase.entrySet().stream()
                .map(entry -> new PlanChoiceSegment(entry.getKey(), entry.getValue()))
                .toList();
    }

    private ActionCard.ActionOption planChoiceOption(String planId,
                                                     int index,
                                                     PlanChoiceSpec spec,
                                                     PlanIntent intent,
                                                     Set<String> used) {
        List<PoiDto> pois = new ArrayList<>();
        for (PlanChoiceSegment segment : spec.segments()) {
            findPlanChoiceCandidate(segment.phase(), segment.tags(), intent, used).ifPresent(pois::add);
        }
        List<String> poiIds = pois.stream().map(PoiDto::poiId).toList();
        used.addAll(poiIds);
        String route = pois.stream().map(PoiDto::name).reduce((left, right) -> left + " -> " + right).orElse("");
        String description = route.isBlank() ? spec.description() : spec.description() + " 推荐：" + route + "。";
        return new ActionCard.ActionOption("plan-choice-" + planId + "-" + index,
                "方案 " + index + "：" + spec.title(), description, "BUILD_PLAN", null,
                "BUILD_PLAN:choice-" + index, null, poiIds, null, "PLAN_CHOICE");
    }

    private PlanChoiceContext planChoiceContext(PlanIntent intent) {
        String prompt = intent == null || intent.originalPrompt() == null
                ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
        boolean family = intent != null && (intent.hasChildren() || intent.childAge() != null
                || containsAny(intent.participants(), "孩子", "亲子", "family"));
        boolean wantsDrinks = intent != null && ((intent.drinkPreference() != null && !intent.drinkPreference().isBlank())
                || containsAny(prompt, "喝酒", "酒吧", "清吧", "小酌", "精酿", "鸡尾酒", "bar", "pub"));
        boolean wantsMovie = containsAny(prompt, "电影", "影院", "影城", "看一场", "看场", "movie", "cinema");
        boolean indoor = intent != null && (intent.weatherSensitive()
                || containsAny(prompt, "室内", "下雨", "少晒", "不想户外", "别户外"));
        boolean lowQueue = containsAny(prompt, "少排队", "少等", "不排队", "别排队", "排队少");
        boolean nearby = intent == null || !"WIDE".equalsIgnoreCase(intent.locationScope())
                || containsAny(prompt, "附近", "少绕路", "少折腾", "商圈", "地点范围", "就近");
        boolean compact = intent != null && "COMPACT".equalsIgnoreCase(intent.pace())
                || containsAny(prompt, "紧凑", "多安排一个点", "多安排");
        boolean relaxed = intent != null && "RELAXED".equalsIgnoreCase(intent.pace())
                || containsAny(prompt, "轻松", "低压力", "别太赶", "慢一点");
        String titlePrefix = family ? "亲子" : wantsDrinks ? "朋友小酌" : "同行";
        String scopeText = nearby ? "同商圈/附近" : "可接受范围内";
        String constraintText = String.join("、", planChoiceConstraintWords(indoor, lowQueue, nearby, relaxed, compact));
        return new PlanChoiceContext(prompt, family, wantsDrinks, wantsMovie, indoor, lowQueue, nearby, compact,
                relaxed, titlePrefix, scopeText, constraintText);
    }

    private List<String> planChoiceConstraintWords(boolean indoor,
                                                   boolean lowQueue,
                                                   boolean nearby,
                                                   boolean relaxed,
                                                   boolean compact) {
        List<String> words = new ArrayList<>();
        if (indoor) words.add("室内");
        if (lowQueue) words.add("少排队");
        if (nearby) words.add("少绕路");
        if (relaxed) words.add("轻松节奏");
        if (compact) words.add("更紧凑");
        if (words.isEmpty()) words.add("可执行");
        return words;
    }

    private List<String> planChoicePhases(PlanIntent intent, PlanChoiceContext context) {
        LinkedHashSet<String> phases = new LinkedHashSet<>();
        if (intent != null && intent.requestedSegments() != null) {
            for (String segment : intent.requestedSegments()) {
                String phase = normalizePlanChoicePhase(segment);
                if (!phase.isBlank()) phases.add(phase);
            }
        }
        if (phases.isEmpty()) {
            phases.add("LEISURE");
            phases.add("DINING");
        }
        if (context.wantsMovie()) {
            phases.remove("LEISURE");
            phases.remove("ACTIVITY");
            phases.add("CINEMA");
        }
        if (context.wantsDrinks()) phases.add("DRINKS");
        if (phases.contains("DINING")) {
            phases.remove("DINING");
            phases.add("DINING");
        }
        if (context.wantsMovie()) {
            phases.remove("CINEMA");
            phases.add("CINEMA");
        }
        if (context.wantsDrinks()) {
            phases.remove("DRINKS");
            phases.add("DRINKS");
        }
        return List.copyOf(phases);
    }

    private String normalizePlanChoicePhase(String segment) {
        if (segment == null || segment.isBlank()) return "";
        String normalized = segment.trim().toUpperCase(Locale.ROOT);
        if ("ACTIVITY".equals(normalized)) return "LEISURE";
        if ("CINEMA".equals(normalized)) return "CINEMA";
        if ("LEISURE".equals(normalized) || "DINING".equals(normalized) || "DRINKS".equals(normalized)) {
            return normalized;
        }
        return normalized;
    }

    private List<PlanChoiceSegment> planChoiceSegments(List<String> phases,
                                                       PlanChoiceContext context,
                                                       String style) {
        return phases.stream()
                .map(phase -> new PlanChoiceSegment(phase, planChoiceTags(phase, context, style)))
                .toList();
    }

    private List<String> planChoiceTags(String phase, PlanChoiceContext context, String style) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        if (context.nearby()) tags.add("NEARBY");
        if (context.indoor() && !"DINING".equals(phase) && !"DRINKS".equals(phase)) tags.add("INDOOR");
        if (context.family()) tags.add("CHILD_FRIENDLY");
        if ("quiet".equals(style)) tags.add("QUIET");
        if ("compact".equals(style)) tags.add("NEARBY");

        if ("DINING".equals(phase)) {
            tags.add("SOCIAL_DINING");
            if ("quiet".equals(style)) tags.add("QUIET");
            if (containsAny(context.prompt(), "烧烤", "烤串", "bbq")) tags.add("BBQ");
            if (containsAny(context.prompt(), "咖啡", "甜品", "饮品", "果汁")) tags.add("DESSERT");
        } else if ("DRINKS".equals(phase)) {
            tags.add("BAR");
            tags.add("DRINKS");
            if ("quiet".equals(style) || containsAny(context.prompt(), "清吧", "安静", "小酌")) tags.add("QUIET_BAR");
            if ("compact".equals(style)) tags.add("LATE_NIGHT");
        } else if ("CINEMA".equals(phase)) {
            tags.add("INDOOR");
            tags.add("SOCIAL_ENTERTAINMENT");
            if ("quiet".equals(style)) tags.add("QUIET");
            if ("compact".equals(style)) tags.add("LATE_NIGHT");
        } else {
            if ("quiet".equals(style)) tags.add("COFFEE");
            if ("compact".equals(style)) tags.add("SOCIAL_ENTERTAINMENT");
            if (!tags.contains("INDOOR") && !context.family()) tags.add("SOCIAL_ENTERTAINMENT");
        }
        return List.copyOf(tags);
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

    private boolean containsAny(String text, String... needles) {
        if (text == null || text.isBlank()) return false;
        String normalized = text.toLowerCase(Locale.ROOT);
        for (String needle : needles) {
            if (needle != null && normalized.contains(needle.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
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
        PendingAction pending = context == null ? null : context.pendingAction();
        if (candidateChainWorkflow.tryHandlePatch(context, pending, patch, emitter)) {
            return;
        }
        if (shouldApplyMoviePatchImmediately(context, patch)) {
            applyMoviePatchImmediately(context, patch, emitter);
            return;
        }
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
        applyDeltaAndMaybeRecommend(context, delta, patch, shouldAutoRecommendAfterPatch(source, patch), emitter);
    }

    private boolean shouldDeferPatchInConsultation(ContextPack context, PlanPatch patch) {
        if (context == null || patch == null) return false;
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        PlanIntent intent = draft.intent();
        if (intent == null || !intent.isConsultingMode()) return false;
        boolean hasBusinessTimeline = draft.timeline() != null && draft.timeline().stream()
                .anyMatch(step -> step != null && !step.isTransit() && step.poiId() != null && !step.poiId().isBlank());
        if (shouldApplyMoviePatchImmediately(context, patch)) {
            return false;
        }
        if (!hasBusinessTimeline && isMoviePatch(patch) && !isDiningPatch(patch)) {
            return true;
        }
        if (patch.requirements() != null && patch.requirements().prefer().contains("CONTEXT_READY")
                && hasConcretePlanningWindow(intent)) {
            return false;
        }
        return !hasBusinessTimeline;
    }

    private boolean hasConcretePlanningWindow(PlanIntent intent) {
        return intent != null
                && intent.startTime() != null && !intent.startTime().isBlank()
                && intent.endTime() != null && !intent.endTime().isBlank()
                && intent.totalMinutes() > 0
                && intent.headcount() > 0;
    }

    private boolean shouldApplyMoviePatchImmediately(ContextPack context, PlanPatch patch) {
        if (context == null || patch == null) return false;
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        PlanIntent intent = draft == null ? null : draft.intent();
        if (intent == null || !intent.isConsultingMode()) return false;
        if (!isMoviePatch(patch) || isDiningPatch(patch) || !hasMovieTime(patch)) return false;
        if (isMovieMealContext(context)) return false;
        boolean hasBusinessTimeline = draft.timeline() != null && draft.timeline().stream()
                .anyMatch(step -> step != null && !step.isTransit() && step.poiId() != null && !step.poiId().isBlank());
        return !hasBusinessTimeline;
    }

    private boolean isMovieMealContext(ContextPack context) {
        if (context == null) return false;
        PendingAction pending = context.pendingAction();
        if (pending != null) {
            if ("DINING_LOCKED_PLAN".equalsIgnoreCase(pending.workflowType())
                    || "PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())) {
                return true;
            }
            if (pending.selectedPatch() != null && isDiningPatch(pending.selectedPatch())) {
                return true;
            }
        }
        if (context.constraints() == null || context.constraints().experiencePreference() == null) return false;
        List<String> biases = context.constraints().experiencePreference().activityBiases();
        return biases.contains("movie") && (biases.contains("dining") || biases.contains("dessert"));
    }

    private void applyMoviePatchImmediately(ContextPack context, PlanPatch patch, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        String candidateName = extractCandidateName(context, patch);
        PendingAction pending = new PendingAction("MOVIE_SCHEDULING",
                null,
                null,
                List.of("adjust movie", "add optional activity"),
                "MOVIE",
                patch,
                candidateName,
                List.of(),
                baseSlotsForMovieOnly(context, patch),
                true);
        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: movie_schedule immediate",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION",
                patch, null));
        PlanResponse response = planEditorEngine.applyPendingSelectedPatch(draft, pending);
        emitPendingPlanResponse(context, pending, response, emitter);
    }

    private Map<String, Object> baseSlotsForMovieOnly(ContextPack context, PlanPatch patch) {
        Map<String, Object> slots = new LinkedHashMap<>(baseSlotsFromContext(context));
        slots.putIfAbsent("headcount", 1);
        selectedMetadata(patch, "MOVIE_TIME:")
                .filter(value -> !value.isBlank())
                .ifPresent(value -> slots.putIfAbsent("startTime", value));
        return Map.copyOf(slots);
    }

    private PlanPatch mergePatches(PlanPatch oldPatch, PlanPatch newPatch) {
        if (oldPatch == null) return newPatch;
        if (newPatch == null) return oldPatch;

        List<String> prefer = new ArrayList<>();
        if (oldPatch.requirements() != null && oldPatch.requirements().prefer() != null) {
            prefer.addAll(oldPatch.requirements().prefer());
        }
        if (newPatch.requirements() != null && newPatch.requirements().prefer() != null) {
            for (String p : newPatch.requirements().prefer()) {
                if (!prefer.contains(p)) {
                    prefer.add(p);
                }
            }
        }

        List<String> avoid = new ArrayList<>();
        if (oldPatch.requirements() != null && oldPatch.requirements().avoid() != null) {
            avoid.addAll(oldPatch.requirements().avoid());
        }
        if (newPatch.requirements() != null && newPatch.requirements().avoid() != null) {
            for (String a : newPatch.requirements().avoid()) {
                if (!avoid.contains(a)) {
                    avoid.add(a);
                }
            }
        }

        List<String> keep = new ArrayList<>();
        if (oldPatch.requirements() != null && oldPatch.requirements().keep() != null) {
            keep.addAll(oldPatch.requirements().keep());
        }
        if (newPatch.requirements() != null && newPatch.requirements().keep() != null) {
            for (String k : newPatch.requirements().keep()) {
                if (!keep.contains(k)) {
                    keep.add(k);
                }
            }
        }

        String pace = newPatch.requirements().pace() != null ? newPatch.requirements().pace() : oldPatch.requirements().pace();
        String budgetLevel = newPatch.requirements().budgetLevel() != null ? newPatch.requirements().budgetLevel() : oldPatch.requirements().budgetLevel();
        String transport = newPatch.requirements().preferredTransportMode() != null ? newPatch.requirements().preferredTransportMode() : oldPatch.requirements().preferredTransportMode();
        boolean endEarlier = newPatch.requirements().endEarlier() || oldPatch.requirements().endEarlier();

        PlanPatch.Requirements reqs = new PlanPatch.Requirements(keep, avoid, prefer, pace, budgetLevel, transport, endEarlier);
        PlanPatch.Target target = newPatch.target() != null ? newPatch.target() : oldPatch.target();

        return new PlanPatch(newPatch.intent(), newPatch.editType(), target, reqs, newPatch.requiresSearch());
    }

    private void emitConsultationPatchDeferral(ContextPack context, PlanPatch patch, Consumer<SseEvent> emitter) {
        emitTool(emitter, "OBSERVATION", 2, context,
                "plan.edit deferred: consultation draft has no timeline yet");
        String candidateName = extractCandidateName(context, patch);
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        if (context != null && draft != null) {
            PendingAction currentPending = context.pendingAction();
            if (isMoviePatch(patch) && !isDiningPatch(patch)
                    && (currentPending == null || currentPending.selectedPatch() == null
                    || !isMoviePatch(currentPending.selectedPatch()) || isDiningPatch(currentPending.selectedPatch()))) {
                emitDiningCandidatesAfterMovieSelection(context, draft, patch, candidateName, emitter);
                return;
            }
            PlanPatch effectivePatch = patch;
            String effectiveCandidateName = candidateName;
            if (currentPending != null && currentPending.selectedPatch() != null) {
                effectivePatch = mergePatches(currentPending.selectedPatch(), patch);
                if (currentPending.selectedLabel() != null && candidateName != null) {
                    effectiveCandidateName = currentPending.selectedLabel() + " + " + candidateName;
                } else if (candidateName == null) {
                    effectiveCandidateName = currentPending.selectedLabel();
                }
            }
            PendingAction pending = effectiveCandidateName != null
                    ? pendingForDeferredPatch(context, effectivePatch, effectiveCandidateName)
                    : new PendingAction("ASK_CONTEXT", null, null,
                    List.of("time", "location", "headcount", "build plan"),
                    "CONTEXTUAL_RESEARCH", effectivePatch, null,
                    List.of("timeWindow", "locationScope", "headcount"), baseSlotsFromContext(context), true);
            if (context.pendingAction() != null && context.pendingAction().collectedSlots() != null) {
                pending = pending.mergeCollectedSlots(context.pendingAction().collectedSlots());
            }
            sessionStateStore.savePending(draft.planId(), draft.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Need concrete planning window before applying candidate: " + effectiveCandidateName, Instant.now()));
            SlotCollectionService.SlotCollectionPrompt slotPrompt =
                    slotCollectionService.forPending(draft.planId(), pending);
            String message = effectiveCandidateName == null
                    ? slotPrompt.message()
                    : "我先记住「" + effectiveCandidateName + "」。" + slotPrompt.message();
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, draft.planId(), draft.intent(), draft.orderIntents(),
                    "PENDING_CONFIRMATION", pending.selectedPatch(), slotPrompt.card()));
            return;
        }
        if (candidateName != null) {
            PlanPatch effectivePatch = patch;
            String effectiveCandidateName = candidateName;
            if (context.pendingAction() != null && context.pendingAction().selectedPatch() != null) {
                effectivePatch = mergePatches(context.pendingAction().selectedPatch(), patch);
                if (context.pendingAction().selectedLabel() != null && candidateName != null) {
                    effectiveCandidateName = context.pendingAction().selectedLabel() + " + " + candidateName;
                } else if (candidateName == null) {
                    effectiveCandidateName = context.pendingAction().selectedLabel();
                }
            }
            PendingAction pending = pendingForDeferredPatch(context, effectivePatch, effectiveCandidateName);
            if (context.pendingAction() != null && context.pendingAction().collectedSlots() != null) {
                pending = pending.mergeCollectedSlots(context.pendingAction().collectedSlots());
            }
            sessionStateStore.savePending(draft.planId(), draft.userId(),
                    pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Need concrete planning window before applying candidate: " + effectiveCandidateName, Instant.now()));
            String message = "我先记住你选的「" + effectiveCandidateName
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

    private void emitDiningCandidatesAfterMovieSelection(ContextPack context,
                                                         PlanExecutionStore.DraftPlan draft,
                                                         PlanPatch moviePatch,
                                                         String movieName,
                                                         Consumer<SseEvent> emitter) {
        PlanPatch diningPatch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, "DINING", "DINING", null, null),
                new PlanPatch.Requirements(List.of(), safeAvoid(moviePatch), diningPreferences(moviePatch),
                        null, null, null, false),
                true);
        CandidateCardResult result = candidateCardService.buildCandidateCard(draft, diningPatch);
        PendingAction pending = new PendingAction("MOVIE_SCHEDULING",
                result.candidateSet().candidateSetId(),
                result.candidateSet().targetSegmentId(),
                List.of("choose dining", "time", "location", "headcount"),
                "MOVIE",
                moviePatch,
                movieName,
                List.of(),
                baseSlotsFromContext(context),
                true);
        if (!result.candidateSet().items().isEmpty()) {
            sessionStateStore.saveCandidates(draft.planId(), draft.userId(), result.candidateSet(), pending,
                    new RecentEvent(RecentEventType.CANDIDATES_RECOMMENDED,
                            "Dining candidates after movie selection", Instant.now()));
        } else {
            sessionStateStore.savePending(draft.planId(), draft.userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Movie selected; dining candidates unavailable", Instant.now()));
        }
        String content = movieName == null || movieName.isBlank()
                ? "我先记住这场电影。再选一个就餐地点，我会把电影和餐厅一起放进行程。"
                : "我先记住「" + movieName + "」。再选一个就餐地点，我会把电影和餐厅一起放进行程。";
        emitter.accept(new SseEvent("ACTION", 3, "consultation.movie.locked: find dining candidates",
                List.of(), null, null, null, null, draft.planId(), draft.intent(),
                draft.orderIntents(), "OPTIONS_READY", moviePatch, result.card()));
        emitter.accept(new SseEvent("FINISH", 4, content, List.of(), "SUCCESS", "", "",
                null, draft.planId(), draft.intent(), draft.orderIntents(),
                "OPTIONS_READY", moviePatch, result.card()));
    }

    private List<String> diningPreferences(PlanPatch patch) {
        List<String> prefer = new ArrayList<>();
        if (patch != null && patch.requirements() != null) {
            for (String value : patch.requirements().prefer()) {
                if (value == null || value.startsWith(runtime.getSelectedPoiPrefix())
                        || value.startsWith("MOVIE_") || "CONTEXT_READY".equals(value)) {
                    continue;
                }
                if (!prefer.contains(value)) {
                    prefer.add(value);
                }
            }
        }
        if (!prefer.contains("NEARBY")) {
            prefer.add("NEARBY");
        }
        return List.copyOf(prefer);
    }

    private List<String> safeAvoid(PlanPatch patch) {
        return patch == null || patch.requirements() == null || patch.requirements().avoid() == null
                ? List.of()
                : patch.requirements().avoid();
    }

    private PendingAction pendingForDeferredPatch(ContextPack context, PlanPatch patch, String candidateName) {
        Map<String, Object> slots = baseSlotsFromContext(context);
        boolean movie = selectedMetadata(patch, "MOVIE_TITLE:").isPresent()
                || selectedMetadata(patch, "MOVIE_ID:").isPresent();
        if (!movie && patch.requirements() != null) {
            for (String pref : patch.requirements().prefer()) {
                if (pref != null && pref.startsWith("SELECTED_POI:")) {
                    String poiId = pref.substring("SELECTED_POI:".length()).trim();
                    if (replacementSearchEngine.isCinema(poiId)) {
                        movie = true;
                        break;
                    }
                }
            }
        }
        String phase = patch == null || patch.target() == null ? "" :
                firstNonBlank(patch.target().phase(), patch.target().activityType());
        boolean dining = "DINING".equalsIgnoreCase(phase) || "RESTAURANT".equalsIgnoreCase(phase);
        if (!dining && patch.requirements() != null) {
            for (String pref : patch.requirements().prefer()) {
                if (pref != null && pref.startsWith("SELECTED_POI:")) {
                    String poiId = pref.substring("SELECTED_POI:".length()).trim();
                    if (replacementSearchEngine.isRestaurant(poiId)) {
                        dining = true;
                        break;
                    }
                }
            }
        }
        String type = (movie && dining) ? "PLAN_SLOT_FILLING" : (movie ? "MOVIE_SCHEDULING" : (dining ? "PLAN_SLOT_FILLING" : "ASK_CONTEXT"));
        String workflowType = (movie && dining) ? "DINING_LOCKED_PLAN" : (movie ? "MOVIE" : (dining ? "DINING_LOCKED_PLAN" : "CONTEXTUAL_RESEARCH"));
        List<String> requiredSlots = (movie && dining)
                ? List.of("startTime", "duration", "locationScope", "headcount", "orderPreference")
                : (movie ? List.of("timeWindow", "locationScope", "headcount")
                         : List.of("startTime", "duration", "locationScope", "headcount", "orderPreference"));
        return new PendingAction(type, null, null, List.of("time", "location", "headcount", "build plan"),
                workflowType, patch, candidateName, requiredSlots, slots, true);
    }

    private boolean isMoviePatch(PlanPatch patch) {
        if (patch == null) return false;
        if (selectedMetadata(patch, "MOVIE_TITLE:").isPresent()
                || selectedMetadata(patch, "MOVIE_ID:").isPresent()) {
            return true;
        }
        return selectedPoiIds(patch).stream().anyMatch(poiId ->
                replacementSearchEngine != null && replacementSearchEngine.isCinema(poiId));
    }

    private boolean isDiningPatch(PlanPatch patch) {
        if (patch == null) return false;
        String phase = patch.target() == null ? "" : firstNonBlank(patch.target().phase(), patch.target().activityType());
        if ("DINING".equalsIgnoreCase(phase) || "RESTAURANT".equalsIgnoreCase(phase)) {
            return true;
        }
        return selectedPoiIds(patch).stream().anyMatch(poiId ->
                replacementSearchEngine != null && replacementSearchEngine.isRestaurant(poiId));
    }

    private List<String> selectedPoiIds(PlanPatch patch) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return List.of();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(runtime.getSelectedPoiPrefix()))
                .map(value -> value.substring(runtime.getSelectedPoiPrefix().length()).trim())
                .filter(value -> !value.isBlank())
                .toList();
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
        if (candidateChainWorkflow.tryHandleCandidate(context, pending, candidateSet, item, emitter)) {
            return;
        }
        if (shouldApplyMoviePatchImmediately(context, item.planPatch())) {
            applyMoviePatchImmediately(context, item.planPatch(), emitter);
            return;
        }
        if (shouldDeferPatchInConsultation(context, item.planPatch())) {
            emitConsultationPatchDeferral(context, item.planPatch(), emitter);
            return;
        }
        sessionStateStore.clearPending(context.planId(),
                new RecentEvent(RecentEventType.CANDIDATE_SELECTED, item.poi().name(), Instant.now()));
        applyDeltaAndMaybeRecommend(context, PlanDelta.fromPatch(item.planPatch()), item.planPatch(), false, emitter);
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
                true,
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
                                             boolean allowAutoRecommend,
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
        if (allowAutoRecommend && replacementSearchEngine != null) {
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
                response.version(), response.planStatus(), response.weather(), summary, List.of()));
    }

    private void emitCandidateCard(ContextPack context, PlanPatch patch, Consumer<SseEvent> emitter) {
        PlanExecutionStore.DraftPlan draft = getDraft(context);
        CandidateCardResult result = candidateCardService.buildCandidateCard(draft, patch);
        if (!result.candidateSet().items().isEmpty()) {
            saveCandidateState(draft, result.candidateSet(), result.candidateSet().targetSegmentId());
        }
        emitter.accept(new SseEvent("ACTION", 2, "poi.search.replacement: find candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, null, null,
                List.of(), List.of(), draft.version(), draft.status(), null));
        emitter.accept(new SseEvent("OBSERVATION", 2, "poi.search.replacement result: " + result.card().options().size() + " candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card(), null,
                List.of(), List.of(), draft.version(), draft.status(), null));
        emitter.accept(new SseEvent("ACTION", 3, "card.render: replacement candidates",
                context.draft().timeline(), null, null, null, null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card(), null,
                List.of(), List.of(), draft.version(), draft.status(), null));
        emitter.accept(new SseEvent("FINISH", 4, textService.candidatePrompt(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.planId(),
                draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION", patch, result.card(), null,
                List.of(), List.of(), draft.version(), draft.status(), null));
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

    private boolean shouldAutoRecommendAfterPatch(String source, PlanPatch patch) {
        if (patch == null) return false;
        if (patchFactory.selectedPoiId(patch).isPresent()) return false;
        String normalizedSource = source == null ? "" : source.toLowerCase(Locale.ROOT);
        return !normalizedSource.contains("action-card:submit_patch");
    }

    private Optional<PlanStep> findAutoRecommendStep(List<PlanStep> timeline) {
        return timeline.stream()
                .filter(step -> !step.isTransit())
                .filter(step -> "LEISURE".equalsIgnoreCase(step.phase())
                        || ("DINING".equalsIgnoreCase(step.phase()) && (step.poiId() == null || step.poiId().isBlank()))
                        || (step.poiName() != null && (step.poiName().contains("自由") || step.poiName().contains("散步") || step.poiName().contains("用餐") || step.poiName().contains("吃饭"))))
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

    private PendingAction mergePlanChoiceCollectedSlots(PendingAction pending, Map<String, Object> slots) {
        if (pending == null || slots == null || slots.isEmpty()) return pending;
        Map<String, Object> collected = new LinkedHashMap<>(slots);
        collected.remove("originalPrompt");
        return collected.isEmpty() ? pending : pending.mergeCollectedSlots(collected);
    }

    private String buildCompletedInitialPrompt(String originalPrompt, Map<String, Object> slots) {
        StringBuilder builder = new StringBuilder(empty(originalPrompt));
        List<String> details = new ArrayList<>();
        List<String> humanDetails = new ArrayList<>();
        String startTime = slotString(slots, "startTime", null);
        String endTime = firstNonBlank(slotString(slots, "endTime", null), slotString(slots, "maxEndTime", null));
        String timeRange = firstNonBlank(slotString(slots, "timeRange", null), slotString(slots, "timeWindow", null));
        if (startTime != null && endTime != null) {
            details.add("time=" + startTime + "-" + endTime);
            humanDetails.add("我计划在 " + startTime + " 到 " + endTime + " 出行");
        } else if (startTime != null) {
            details.add("startTime=" + startTime);
            humanDetails.add("我计划在 " + startTime + " 后出行");
        } else if (timeRange != null) {
            details.add("timeRange=" + timeRange);
        }
        Integer headcount = slotInteger(slots, "headcount", null);
        if (headcount != null && headcount > 0) {
            details.add("headcount=" + headcount);
            humanDetails.add("总共 " + headcount + " 个人");
        }
        String locationScope = slotString(slots, "locationScope", null);
        if (locationScope != null) {
            details.add("locationScope=" + locationScope);
            humanDetails.add("地点范围：" + locationScope);
        }
        if (!details.isEmpty()) {
            builder.append("\n[PLAN_DETAILS] ").append(String.join("; ", details)).append(".");
            if (!humanDetails.isEmpty()) {
                builder.append(" 已补充：").append(String.join("，", humanDetails)).append("。");
            }
        }
        return builder.toString();
    }

    private PlanIntent completedInitialIntent(PlanIntent base, String completedPrompt, Map<String, Object> slots) {
        PlanIntent fallback = base == null
                ? new PlanIntent(1, List.of(), null, null, 0, null,
                List.of(), List.of(), null, null, completedPrompt, false)
                : base;
        String range = firstNonBlank(slotString(slots, "timeRange", null), slotString(slots, "timeWindow", null));
        String startTime = firstNonBlank(slotString(slots, "startTime", null), timeRangeStart(range), fallback.startTime());
        String endTime = firstNonBlank(slotString(slots, "endTime", null), slotString(slots, "maxEndTime", null),
                timeRangeEnd(range), fallback.endTime());
        Integer durationSlot = firstNonNull(slotInteger(slots, "durationMinutes", null),
                slotInteger(slots, "maxDurationMinutes", null));
        int totalMinutes = durationSlot != null && durationSlot > 0
                ? durationSlot
                : minutesBetweenSafe(startTime, endTime, fallback.totalMinutes());
        int headcount = slotInteger(slots, "headcount", fallback.headcount());
        String locationScope = firstNonBlank(slotString(slots, "locationScope", null), fallback.locationScope());
        String pace = firstNonBlank(slotString(slots, "pace", null), fallback.pace());
        String budgetLevel = firstNonBlank(slotString(slots, "budgetLevel", null), fallback.budgetLevel());
        String transport = firstNonBlank(slotString(slots, "preferredTransportMode", null),
                fallback.preferredTransportMode());
        return new PlanIntent(headcount, fallback.participants(), startTime, endTime, totalMinutes,
                fallback.sceneType(), fallback.requestedSegments(), fallback.dietaryConstraints(),
                fallback.drinkPreference(), locationScope, completedPrompt, pace, budgetLevel,
                fallback.hasChildren(), fallback.childAge(), transport, fallback.avoid(),
                fallback.mustHave(), fallback.weatherSensitive(), false);
    }

    private String slotString(Map<String, Object> slots, String key, String fallback) {
        Object value = slots == null ? null : slots.get(key);
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private Integer slotInteger(Map<String, Object> slots, String key, Integer fallback) {
        Object value = slots == null ? null : slots.get(key);
        if (value instanceof Number number) return number.intValue();
        if (value == null) return fallback;
        Matcher matcher = Pattern.compile("\\d+").matcher(String.valueOf(value));
        return matcher.find() ? Integer.parseInt(matcher.group()) : fallback;
    }

    private String timeRangeStart(String range) {
        return switch (normalizeRange(range)) {
            case "MORNING" -> "10:00";
            case "NOON" -> "12:00";
            case "AFTERNOON" -> "14:00";
            case "EVENING", "NIGHT" -> "19:00";
            default -> null;
        };
    }

    private String timeRangeEnd(String range) {
        return switch (normalizeRange(range)) {
            case "MORNING" -> "12:30";
            case "NOON" -> "14:00";
            case "AFTERNOON" -> "18:00";
            case "EVENING", "NIGHT" -> "22:00";
            default -> null;
        };
    }

    private String normalizeRange(String range) {
        return range == null ? "" : range.trim().toUpperCase(Locale.ROOT);
    }

    private int minutesBetweenSafe(String startTime, String endTime, int fallback) {
        int start = toMinutes(startTime);
        int end = toMinutes(endTime);
        int minutes = end - start;
        return minutes > 0 ? minutes : Math.max(0, fallback);
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
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

    private record PlanChoiceCardResult(
            ActionCard card,
            String source
    ) {}

    private record PlanChoiceSpecResult(
            List<PlanChoiceSpec> specs,
            String source
    ) {}

    private record PlanChoiceSpec(
            String title,
            String description,
            List<PlanChoiceSegment> segments
    ) {}

    private record PlanChoiceSegment(
            String phase,
            List<String> tags
    ) {}

    private record PlanChoiceContext(
            String prompt,
            boolean family,
            boolean wantsDrinks,
            boolean wantsMovie,
            boolean indoor,
            boolean lowQueue,
            boolean nearby,
            boolean compact,
            boolean relaxed,
            String titlePrefix,
            String scopeText,
            String constraintText
    ) {}
}
