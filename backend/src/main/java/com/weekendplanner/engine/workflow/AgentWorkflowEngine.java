package com.weekendplanner.engine.workflow;

import com.weekendplanner.engine.context.AgentContext;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

@Component
public class AgentWorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(AgentWorkflowEngine.class);

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

    private final InteractionRouter interactionRouter;
    private final ConversationalQaService conversationalQaService;
    private final PendingSlotFiller pendingSlotFiller = new PendingSlotFiller();
    private final FallbackSlotExtractor fallbackSlotExtractor = new FallbackSlotExtractor();

    @Autowired
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
                sessionStateStore, objectMapper, new AgentRuntimeProperties(), null, null, null, null, null, null, null, null);
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
        InitialRouteCommand route = initialRequestRouter.route(request.prompt());
        if (route.mode() == InitialRouteMode.CONSULT_CHAT && consultationWorkflow != null) {
            return consultationWorkflow.start(request, route, ignored -> {});
        }
        if (route.mode() == InitialRouteMode.RESEARCH_AND_RENDER && researchRenderWorkflow != null) {
            return researchRenderWorkflow.execute(request, route, ignored -> {});
        }
        if (route.mode() == InitialRouteMode.ASK_CLARIFICATION) {
            return createClarificationDraft(request, route, ignored -> {});
        }
        if (route.mode() == InitialRouteMode.CREATE_PLAN
                && shouldOfferStructuredChoices(request.prompt())) {
            return createStructuredChoiceDraft(request, ignored -> {});
        }
        PlanResponse response = fastPlanEngine.executePlan(request);
        rememberDraft(response.planId());
        return response;
    }

    public PlanResponse createPlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        InitialRouteCommand route = initialRequestRouter.route(request.prompt());
        if (route.mode() == InitialRouteMode.CONSULT_CHAT && consultationWorkflow != null) {
            return consultationWorkflow.start(request, route, emitter);
        }
        if (route.mode() == InitialRouteMode.RESEARCH_AND_RENDER && researchRenderWorkflow != null) {
            return researchRenderWorkflow.execute(request, route, emitter);
        }
        if (route.mode() == InitialRouteMode.ASK_CLARIFICATION) {
            return createClarificationDraft(request, route, emitter);
        }
        if (route.mode() == InitialRouteMode.CREATE_PLAN
                && shouldOfferStructuredChoices(request.prompt())) {
            return createStructuredChoiceDraft(request, emitter);
        }
        PlanResponse response = fastPlanEngine.executePlanStreaming(request, emitter);
        rememberDraft(response.planId());
        return response;
    }

    private PlanResponse createStructuredChoiceDraft(PlanRequest request, Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent intent = intentExtractor == null ? null : intentExtractor.extract(request.prompt());
        ActionCard card = structuredPlanChoiceCard(planId);
        String content = structuredPlanChoiceMessage();
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                planId, request.userId(), intent, List.of(), List.of(), content);
        executionStore.save(draft);
        sessionStateStore.syncDraft(draft);
        emitter.accept(new SseEvent("START", 0, "plan.options: complete request -> offer route choices",
                List.of(), null, null, null, null, planId, intent, List.of(), "OPTIONS_READY"));
        emitter.accept(new SseEvent("FINISH", 1, content, List.of(), "SUCCESS", "", content,
                null, planId, intent, List.of(), "OPTIONS_READY", null, card));
        return new PlanResponse(planId, request.userId(), "SUCCESS", "", List.of(), List.of(), "", content,
                null, intent, List.of(), "OPTIONS_READY");
    }

    private boolean shouldOfferStructuredChoices(String prompt) {
        String normalized = prompt == null ? "" : prompt.toUpperCase(Locale.ROOT);
        if (normalized.contains("[BUILD_SELECTED_PLAN]") || normalized.contains("BUILD_SELECTED_PLAN")) {
            return false;
        }
        return initialRequestRouter.isCompleteStructuredPlanRequest(prompt);
    }

    private ActionCard structuredPlanChoiceCard(String planId) {
        return new ActionCard(
                "structured-plan-options-" + planId,
                "选择一个方案来构建计划",
                "挑选一条路线想法，PlanPal 将为您一键合成完整时间线。",
                List.of(
                        new ActionCard.ActionOption("family-easy", "亲子轻松吃逛",
                                "儿童探索馆 + 轻食，路线短，孩子有得玩，大人也好聊天。",
                                "BUILD_PLAN", null, "BUILD_PLAN:family_easy", null, List.of("P008", "P011"),
                                null, "PLAN_CHOICE"),
                        new ActionCard.ActionOption("indoor-safe", "室内稳妥雨天版",
                                "展览 + 甜品/果汁，天气风险低，节奏安静不折腾。",
                                "BUILD_PLAN", null, "BUILD_PLAN:indoor_safe", null, List.of("P003", "P028"),
                                null, "PLAN_CHOICE"),
                        new ActionCard.ActionOption("walk-and-eat", "朋友散步好吃版",
                                "城市公园 + 小吃街，边走边吃，适合朋友 and 孩子一起放松。",
                                "BUILD_PLAN", null, "BUILD_PLAN:walk_and_eat", null, List.of("P006", "P016"),
                                null, "PLAN_CHOICE")
                ),
                "也可以补充：更安静、别太贵、少走路、下雨也合适",
                true,
                "PLAN_CHOICE");
    }

    private String structuredPlanChoiceMessage() {
        return """
                根据这次出行信息，我先给你三条都比较轻松的路线。你可以先选一个方向，我可以为你构建完整的拼图方案。

                方案一：亲子轻松吃逛
                先去 [POI:P008:星海儿童探索馆] 玩一段室内亲子活动，再去 [POI:P011:田园沙拉吧] 吃轻食。路线短、节奏稳，适合 5 岁孩子和朋友一起。

                方案二：室内稳妥雨天版
                从 [POI:P003:城市艺术展览中心] 开始，看展约 1.5-2 小时，之后去 [POI:P028:小橙子果汁咖啡] 坐下来补能。适合怕天气变化、想少走路的情况。

                方案三：朋友散步好吃版
                先在 [POI:P006:湖畔城市公园] 轻松走走，再去 [POI:P016:特色小吃街] 吃点好分享的小吃。更有本地闲逛感，适合孩子放电但不跑太远。
                """;
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
        String interactionSource = mergeSource(source, clientActionId);
        InteractionDecision decision = interactionRouter.route(context, interactionSource, patchPayload);
        emitTool(emitter, "ACTION", 1, context, "interaction.route: route chat turn");
        emitTool(emitter, "OBSERVATION", 1, context, "interaction.route result: command=" + decision.command()
                + ", confidence=" + decision.confidence() + ", reason=" + decision.reason());

        if (decision.command() == InteractionCommand.CONVERSATIONAL_QA) {
            answerContextualQuestion(context, emitter);
            return;
        }
        if (decision.command() == InteractionCommand.CANCEL_PENDING) {
            cancelPendingAction(context, emitter);
            return;
        }
        if (decision.command() == InteractionCommand.SMALLTALK_HELP) {
            answerContextualQuestion(context, emitter);
            return;
        }
        if (decision.command() == InteractionCommand.START_NEW_PLAN) {
            PlanResponse response = fastPlanEngine.executePlanStreaming(new PlanRequest(userId, prompt), emitter);
            rememberDraft(response.planId());
            return;
        }

        PlanPatch directPatch = parsePatchPayload(patchPayload, interactionSource)
                .map(patch -> patchFactory.withSegmentId(patch, segmentId))
                .orElse(null);
        if (decision.command() == InteractionCommand.CONTINUE_WORKFLOW) {
            PendingAction pending = context.pendingAction();
            if (directPatch == null && isPendingType(pending, "MOVIE_SCHEDULING")) {
                continueMovieScheduling(context, decision.pendingSlotPatch(), emitter);
                return;
            }
            if (directPatch == null && isPendingType(pending, "PLAN_SLOT_FILLING")) {
                continueLockedCandidatePlanning(context, decision.pendingSlotPatch(), emitter);
                return;
            }
            if (consultationWorkflow != null && isPreferenceSelection(interactionSource, prompt, context)) {
                consultationWorkflow.continueAfterPreference(context, emitter);
                return;
            }
            if (directPatch == null && consultationWorkflow != null && isConsultationContextTurn(context)) {
                consultationWorkflow.continueAfterContext(context, emitter);
                return;
            }
        }

        AgentCommand command = directPatch == null
                ? agentRouter.route(context)
                : new AgentCommand("APPLY_PATCH", 1.0, segmentId, null, null, Map.of(),
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
            default -> applyFeedbackPatch(context, decision.understanding(), emitter);
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

    private void continueMovieScheduling(AgentContext context, PendingSlotPatch slotPatch, Consumer<SseEvent> emitter) {
        PendingAction pending = mergePendingSlots(context, "MOVIE_SCHEDULING", slotPatch);
        if (pending.selectedPatch() == null) {
            emitPendingQuestion(context, pending, "我还记得你在电影流程里。你可以重新选一场电影，或告诉我想看的电影名/场次。", emitter);
            return;
        }
        if (!hasSlot(pending, "startTime") && !hasMovieTime(pending.selectedPatch())) {
            emitPendingQuestion(context, pending, "我先记住这场电影。你想安排在上午、下午还是晚上？", emitter);
            return;
        }
        if (!hasSlot(pending, "headcount")) {
            emitPendingQuestion(context, pending, "电影我还记着。还差人数：几个人一起看？", emitter);
            return;
        }
        if (planEditorEngine == null) {
            emitPendingQuestion(context, pending, textService.clarificationFallback(), emitter);
            return;
        }

        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: movie_schedule",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION",
                pending.selectedPatch(), null));
        PlanResponse response = planEditorEngine.applyPendingSelectedPatch(context.draft(), pending);
        emitPendingPlanResponse(context, pending, response, emitter);
    }

    private void continueLockedCandidatePlanning(AgentContext context, PendingSlotPatch slotPatch, Consumer<SseEvent> emitter) {
        PendingAction pending = mergePendingSlots(context, "PLAN_SLOT_FILLING", slotPatch);
        if (pending.selectedPatch() == null) {
            emitPendingQuestion(context, pending, "我还记得你选过一个候选，但缺少可执行的地点信息。请重新选一次候选。", emitter);
            return;
        }
        if (!hasSlot(pending, "startTime")) {
            emitPendingQuestion(context, pending, "我先记住这个候选。大概几点开始？", emitter);
            return;
        }
        if (!hasSlot(pending, "headcount")) {
            emitPendingQuestion(context, pending, "还差人数：这次几个人一起？", emitter);
            return;
        }
        if (!hasSlot(pending, "locationScope")) {
            pending = pending.mergeCollectedSlots(Map.of("locationScope", "NEARBY", "assumed:locationScope", true));
            sessionStateStore.savePending(context.draft().planId(), context.draft().userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Assumed nearby scope for locked candidate workflow", Instant.now()));
        }
        if (planEditorEngine == null) {
            emitPendingQuestion(context, pending, textService.clarificationFallback(), emitter);
            return;
        }

        emitter.accept(new SseEvent("ACTION", 2, "pending.workflow.resume: locked_candidate_plan",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION",
                pending.selectedPatch(), null));
        PlanResponse response = planEditorEngine.applyLockedCandidatePlan(context.draft(), pending);
        emitPendingPlanResponse(context, pending, response, emitter);
    }

    private PendingAction mergePendingSlots(AgentContext context, String expectedType, PendingSlotPatch existingSlotPatch) {
        PendingAction pending = context.pendingAction();
        if (pending == null) return null;
        PendingSlotPatch slotPatch = existingSlotPatch == null
                ? pendingSlotFiller.extract(pending, context.userInput(), context.sessionState())
                : existingSlotPatch;
        PendingAction merged = pending.withType(expectedType).mergeCollectedSlots(slotPatch.slots());
        sessionStateStore.savePending(context.draft().planId(), context.draft().userId(), merged,
                new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                        slotPatch.reason().isBlank() ? "Pending workflow resumed" : slotPatch.reason(), Instant.now()));
        return merged;
    }

    private void emitPendingPlanResponse(AgentContext context,
                                         PendingAction pending,
                                         PlanResponse response,
                                         Consumer<SseEvent> emitter) {
        if (response.conflicts().isEmpty() && response.timeline() != null && !response.timeline().isEmpty()) {
            sessionStateStore.clearPending(context.draft().planId(),
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow completed: " + pending.type(), Instant.now()));
        } else {
            sessionStateStore.savePending(context.draft().planId(), context.draft().userId(), pending,
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

    private void emitPendingQuestion(AgentContext context,
                                     PendingAction pending,
                                     String message,
                                     Consumer<SseEvent> emitter) {
        if (pending != null) {
            sessionStateStore.savePending(context.draft().planId(), context.draft().userId(), pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Pending workflow needs more context: " + pending.type(), Instant.now()));
        }
        emitter.accept(new SseEvent("FINISH", 2, message, context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION",
                pending == null ? null : pending.selectedPatch(), null));
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

    private void answerContextualQuestion(AgentContext context, Consumer<SseEvent> emitter) {
        ContextualQaResponse response = conversationalQaService.answer(new ContextualQaRequest(
                context.userInput(), context.draft(), context.sessionState()));
        if (context.draft() != null) {
            sessionStateStore.savePending(context.draft().planId(), context.draft().userId(),
                    context.sessionState() != null ? context.sessionState().pendingAction() : null,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Q&A Turn - User: " + context.userInput() + " | Bot: " + response.answer(), Instant.now()));
        }
        emitter.accept(new SseEvent("FINISH", 2, response.answer(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION",
                null, response.actionCard()));
    }

    private boolean isPreferenceSelection(String source, String prompt, AgentContext context) {
        if (source != null && source.contains("SELECT_PREFERENCE")) return true;
        if (prompt != null && prompt.toUpperCase().contains("PREFERENCE:")) return true;
        PendingAction pending = context.sessionState() == null ? null : context.sessionState().pendingAction();
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

    private boolean isConsultationContextTurn(AgentContext context) {
        PendingAction pending = context.sessionState() == null ? null : context.sessionState().pendingAction();
        return pending != null && "ASK_CONTEXT".equalsIgnoreCase(pending.type());
    }

    private PlanResponse createClarificationDraft(PlanRequest request,
                                                  InitialRouteCommand route,
                                                  Consumer<SseEvent> emitter) {
        String planId = request.planId() == null || request.planId().isBlank()
                ? UUID.randomUUID().toString().substring(0, 8)
                : request.planId();
        PlanIntent extracted = intentExtractor == null ? null : intentExtractor.extract(request.prompt());
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
        String message = route.clarificationQuestion() == null
                ? textService.clarificationFallback()
                : route.clarificationQuestion();
        emitter.accept(new SseEvent("FINISH", 1, message, List.of(), "SUCCESS", "", "",
                null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
        return new PlanResponse(planId, request.userId(), "SUCCESS", message, List.of(), List.of(),
                "", message, null, intent, List.of(), "PENDING_CONFIRMATION");
    }

    private void applyFeedbackPatch(AgentContext context, TurnUnderstanding understanding, Consumer<SseEvent> emitter) {
        if (planEditorEngine == null || planPatchExtractor == null) {
            emitClarification(context, new AgentCommand("MODIFY_PLAN", 0.5, context.segmentId(), null, null,
                    Map.of(), null, "CLARIFY", RouteMode.FAST_WORKFLOW, true,
                    textService.clarificationFallback(), null), emitter);
            return;
        }
        PlanDelta delta = planDeltaExtractor != null
                ? planDeltaExtractor.extract(empty(context.userInput()), context.draft().timeline(),
                context.draft().intent(), understanding)
                : PlanDelta.fromPatch(planPatchExtractor.extract(empty(context.userInput()),
                context.draft().timeline(), context.draft().intent(), understanding));
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
        if (shouldDeferPatchInConsultation(context, patch)) {
            emitConsultationPatchDeferral(context, patch, emitter);
            return;
        }
        if (shouldOfferReplacementCandidates(source, patch)) {
            PlanPatch candidatePatch = "puzzle-replace-preview".equals(source)
                    ? patchFactory.replaceForSegment(context.draft(), context.segmentId())
                    : patch;
            emitCandidateCard(context, candidatePatch, emitter);
            return;
        }
        applyDeltaAndMaybeRecommend(context, delta, patch, emitter);
    }

    private boolean shouldDeferPatchInConsultation(AgentContext context, PlanPatch patch) {
        if (context == null || context.draft() == null || patch == null) return false;
        PlanIntent intent = context.draft().intent();
        if (intent == null || !intent.isConsultingMode()) return false;
        if (patch.requirements() != null && patch.requirements().prefer().contains("CONTEXT_READY")
                && hasConcretePlanningWindow(intent)) {
            return false;
        }
        boolean hasBusinessTimeline = context.draft().timeline() != null && context.draft().timeline().stream()
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

    private void emitConsultationPatchDeferral(AgentContext context, PlanPatch patch, Consumer<SseEvent> emitter) {
        emitTool(emitter, "OBSERVATION", 2, context,
                "plan.edit deferred: consultation draft has no timeline yet");
        String candidateName = extractCandidateName(context, patch);
        if (candidateName != null) {
            PendingAction pending = pendingForDeferredPatch(context, patch, candidateName);
            sessionStateStore.savePending(context.draft().planId(), context.draft().userId(),
                    pending,
                    new RecentEvent(RecentEventType.CONTEXT_UPDATED,
                            "Need concrete planning window before applying candidate: " + candidateName, Instant.now()));
            String message = "我先记住你选的「" + candidateName
                    + "」。现在还差可执行的时间、地点/范围和人数信息；你可以补充一个时间段、活动范围和人数，我会接着当前选择继续排。";
            emitter.accept(new SseEvent("FINISH", 3, message, List.of(), "SUCCESS", "", "",
                    null, context.draft().planId(), context.draft().intent(), context.draft().orderIntents(),
                    "PENDING_CONFIRMATION"));
            return;
        }
        sessionStateStore.savePending(context.draft().planId(), context.draft().userId(),
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
                null, context.draft().planId(), context.draft().intent(), context.draft().orderIntents(),
                "PENDING_CONFIRMATION"));
    }

    private PendingAction pendingForDeferredPatch(AgentContext context, PlanPatch patch, String candidateName) {
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

    private Map<String, Object> baseSlotsFromContext(AgentContext context) {
        if (context == null || context.draft() == null || context.draft().intent() == null) return Map.of();
        return fallbackSlotExtractor.explicitSlotsFromIntent(context.draft().intent());
    }

    private String extractCandidateName(AgentContext context, PlanPatch patch) {
        Optional<String> movieTitle = selectedMetadata(patch, "MOVIE_TITLE:");
        return movieTitle.orElseGet(() -> {
            Optional<String> selectedPoiIdOpt = selectedPoiHint(patch).map(val -> val.substring("SELECTED_POI:".length()));
            if (selectedPoiIdOpt.isPresent() && context.sessionState() != null) {
                String selectedPoiId = selectedPoiIdOpt.get();
                for (CandidateSet set : context.sessionState().lastCandidates()) {
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

    private void applySelectedCandidate(AgentContext context, AgentCommand command, Consumer<SseEvent> emitter) {
        SessionState state = context.sessionState();
        PendingAction pending = state.pendingAction();
        String candidateSetId = command.candidateSetId() == null && pending != null
                ? pending.candidateSetId()
                : command.candidateSetId();
        emitTool(emitter, "ACTION", 2, context, "candidate.select: resolve pending candidate");
        CandidateSet candidateSet = state.lastCandidates().stream()
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
            emitClarification(context, new AgentCommand("MODIFY_PLAN", 0.5, context.segmentId(), null, null,
                    Map.of(), null, "CLARIFY", RouteMode.FAST_WORKFLOW, true,
                    textService.clarificationFallback(), null), emitter);
            return;
        }
        emitter.accept(new SseEvent("START", 0, textService.fastWorkflowStarted(), context.draft().timeline(),
                null, null, null, null, context.draft().planId(), context.draft().intent(),
                context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("ACTION", 3, "plan.edit: " + patch.editType(),
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));

        PlanResponse response = planEditorEngine.applyDelta(context.draft(), delta);
        PlanExecutionStore.DraftPlan updatedDraft = executionStore.find(response.planId()).orElse(context.draft());
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

    private void emitCandidateCard(AgentContext context, PlanPatch patch, Consumer<SseEvent> emitter) {
        CandidateCardResult result = candidateCardService.buildCandidateCard(context.draft(), patch);
        saveCandidateState(context.draft(), result.candidateSet(), result.candidateSet().targetSegmentId());
        emitter.accept(new SseEvent("ACTION", 2, "poi.search.replacement: find candidates",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, null));
        emitter.accept(new SseEvent("OBSERVATION", 2, "poi.search.replacement result: " + result.card().options().size() + " candidates",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
        emitter.accept(new SseEvent("ACTION", 3, "card.render: replacement candidates",
                context.draft().timeline(), null, null, null, null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
        emitter.accept(new SseEvent("FINISH", 4, textService.candidatePrompt(), context.draft().timeline(),
                "SUCCESS", "", context.draft().notificationText(), null, context.draft().planId(),
                context.draft().intent(), context.draft().orderIntents(), "PENDING_CONFIRMATION", patch, result.card()));
    }

    private void emitTool(Consumer<SseEvent> emitter, String type, int step, AgentContext context, String content) {
        emitter.accept(new SseEvent(type, step, content, context.draft().timeline(), null, null, null,
                null, context.draft().planId(), context.draft().intent(), context.draft().orderIntents(),
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
}
