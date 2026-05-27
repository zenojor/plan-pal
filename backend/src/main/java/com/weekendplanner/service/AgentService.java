package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.dto.ToolCallResult;
import com.weekendplanner.engine.AgentRouter;
import com.weekendplanner.engine.AgentRuntimeProperties;
import com.weekendplanner.engine.AgentWorkflowEngine;
import com.weekendplanner.engine.CandidateCardService;
import com.weekendplanner.engine.ConflictDetector;
import com.weekendplanner.engine.ConsultantEngine;
import com.weekendplanner.engine.ContextAssembler;
import com.weekendplanner.engine.FastPlanEngine;
import com.weekendplanner.engine.IntentExtractor;
import com.weekendplanner.engine.IntentValidator;
import com.weekendplanner.engine.PlanDeltaExtractor;
import com.weekendplanner.engine.PlanEditorEngine;
import com.weekendplanner.engine.PlanExecutionStore;
import com.weekendplanner.engine.PlanPatchExtractor;
import com.weekendplanner.engine.PlanPatchFactory;
import com.weekendplanner.engine.ReActEngine;
import com.weekendplanner.engine.RenderTextService;
import com.weekendplanner.engine.RepairOptionGenerator;
import com.weekendplanner.engine.ReplacementSearchEngine;
import com.weekendplanner.engine.RouterRuleBook;
import com.weekendplanner.engine.SessionStateStore;
import com.weekendplanner.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final FastPlanEngine fastPlanEngine;
    private final ReActEngine reactEngine;
    private final PlanExecutionStore executionStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final IntentExtractor intentExtractor;
    private final PlanPatchExtractor planPatchExtractor;
    private final PlanDeltaExtractor planDeltaExtractor;
    private final PlanEditorEngine planEditorEngine;
    private final ReplacementSearchEngine replacementSearchEngine;
    private final ConflictDetector conflictDetector;
    private final RepairOptionGenerator repairOptionGenerator;
    private final AgentWorkflowEngine workflowEngine;
    private final AgentRuntimeProperties runtime;
    private final CandidateCardService candidateCardService;

    @Value("${agent.mode:fast}")
    private String mode;

    @Autowired
    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor,
                        PlanPatchExtractor planPatchExtractor,
                        PlanEditorEngine planEditorEngine,
                        ReplacementSearchEngine replacementSearchEngine,
                        IntentValidator intentValidator,
                        PlanDeltaExtractor planDeltaExtractor,
                        ConflictDetector conflictDetector,
                        RepairOptionGenerator repairOptionGenerator,
                        AgentWorkflowEngine workflowEngine,
                        AgentRuntimeProperties runtime) {
        this.fastPlanEngine = fastPlanEngine;
        this.reactEngine = reactEngine;
        this.executionStore = executionStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.intentExtractor = intentExtractor;
        this.planPatchExtractor = planPatchExtractor;
        this.planDeltaExtractor = planDeltaExtractor;
        this.planEditorEngine = planEditorEngine;
        this.replacementSearchEngine = replacementSearchEngine;
        this.conflictDetector = conflictDetector;
        this.repairOptionGenerator = repairOptionGenerator;
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
        this.candidateCardService = buildCandidateCardService();
        this.workflowEngine = workflowEngine == null ? buildWorkflowEngine() : workflowEngine;
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor,
                        PlanPatchExtractor planPatchExtractor,
                        PlanEditorEngine planEditorEngine,
                        ReplacementSearchEngine replacementSearchEngine,
                        IntentValidator intentValidator,
                        PlanDeltaExtractor planDeltaExtractor,
                        ConflictDetector conflictDetector,
                        RepairOptionGenerator repairOptionGenerator) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper,
                consultantEngine, intentExtractor, planPatchExtractor, planEditorEngine,
                replacementSearchEngine, intentValidator, planDeltaExtractor, conflictDetector,
                repairOptionGenerator, null, null);
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor,
                        PlanPatchExtractor planPatchExtractor,
                        PlanEditorEngine planEditorEngine,
                        ReplacementSearchEngine replacementSearchEngine,
                        IntentValidator intentValidator) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper,
                consultantEngine, intentExtractor, planPatchExtractor, planEditorEngine,
                replacementSearchEngine, intentValidator, null, null, null);
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor,
                        IntentValidator intentValidator) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper,
                consultantEngine, intentExtractor, null, null, null, intentValidator);
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper,
                consultantEngine, intentExtractor, new IntentValidator());
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper,
                null, null, null, null, null, new IntentValidator());
    }

    public PlanResponse plan(PlanRequest request) {
        log.info("[AgentService] plan userId={}, mode={}", request.userId(), mode);
        PlanResponse response = useReactMode() ? reactEngine.executePlan(request) : workflowEngine.createPlan(request);
        workflowEngine.rememberDraft(response.planId());
        return response;
    }

    public SseEmitter planStream(PlanRequest request) {
        SseEmitter emitter = new SseEmitter(runtime.getSseTimeoutMs());
        CompletableFuture.runAsync(() -> {
            try {
                workflowEngine.createPlanStreaming(request, event -> sendSse(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE] planning failed", e);
                sendRawError(emitter, e);
            }
        });
        return emitter;
    }

    public SseEmitter planChatStream(String planId, String userId, String prompt) {
        return planChatStream(planId, userId, prompt, null, null, null, null);
    }

    public SseEmitter planChatStream(String planId,
                                     String userId,
                                     String prompt,
                                     String segmentId,
                                     String source,
                                     String clientActionId,
                                     String patchPayload) {
        executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("Draft plan not found: " + planId));
        SseEmitter emitter = new SseEmitter(runtime.getSseTimeoutMs());
        CompletableFuture.runAsync(() -> {
            try {
                workflowEngine.executeChat(planId, userId, prompt, segmentId, source, clientActionId,
                        patchPayload, event -> sendSse(emitter, event));
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE-Chat] plan chat failed. planId={}, source={}, clientActionId={}", planId, source, clientActionId, e);
                sendRawError(emitter, e);
            }
        });
        return emitter;
    }

    ActionCard createReplacementCandidateCard(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return candidateCardService.buildCandidateCard(draft, patch).card();
    }

    ActionCard createConflictActionCard(PlanExecutionStore.DraftPlan draft) {
        return createConflictActionCard(draft, "");
    }

    ActionCard createConflictActionCard(PlanExecutionStore.DraftPlan draft, String prompt) {
        if (conflictDetector != null && repairOptionGenerator != null) {
            var conflicts = conflictDetector.detect(draft, prompt, null);
            if (!conflicts.isEmpty()) {
                return repairOptionGenerator.toActionCard(conflicts, repairOptionGenerator.generate(conflicts, draft, prompt));
            }
        }
        List<ActionCard.ActionOption> options = new ArrayList<>();
        PlanPatch extendEveningPatch = new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, "EVENING", "DRINKS", "DRINKS", null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                true);
        options.add(new ActionCard.ActionOption("extend-evening", "Extend into evening",
                "Keep the current daytime plan and add the new item later.",
                "SUBMIT_PATCH", null, null, extendEveningPatch, null));
        draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.segmentId() != null && !step.segmentId().isBlank())
                .filter(step -> !"DINING".equalsIgnoreCase(step.phase()))
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .sorted(Comparator.comparingInt(PlanStep::durationMinutes).reversed())
                .limit(2)
                .forEach(step -> {
                    PlanPatch patch = new PlanPatch(
                            "MODIFY_PLAN",
                            "REPLACE",
                            new PlanPatch.Target(step.segmentId(), null, "DRINKS", null, null, null),
                            new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                            true);
                    options.add(new ActionCard.ActionOption("replace-" + step.segmentId(),
                            "Replace " + step.poiName(),
                            "Keep the end time and replace this segment.",
                            "SUBMIT_PATCH", step.segmentId(), null, patch,
                            step.poiId() == null ? List.of() : List.of(step.poiId())));
                });
        return new ActionCard("conflict-resolution", "Plan conflict options",
                "The requested change needs a decision.", options,
                "Tell me your preference directly.", true);
    }

    public ConfirmPlanResponse confirmPlan(String planId, ConfirmPlanRequest request) {
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("Executable draft not found: " + planId));

        if (request.version() > 0 && request.version() != draft.version()) {
            throw new IllegalStateException("Plan version expired. submitted=" + request.version()
                    + ", current=" + draft.version());
        }
        if (request.idempotencyKey() != null && !request.idempotencyKey().isBlank()
                && request.idempotencyKey().equals(draft.idempotencyKey())
                && (draft.status() == PlanStatus.CONFIRMED || draft.status() == PlanStatus.PARTIALLY_BOOKED)) {
            return new ConfirmPlanResponse("", draft.status().name(), List.of(), List.of(),
                    draft.notificationText(), draft.timeline(), draft.version(), draft.status());
        }
        executionStore.updateStatus(planId, PlanStatus.CONFIRMING, request.idempotencyKey());

        int headcount = request.headcount() > 0 ? request.headcount() : draft.intent().headcount();
        List<PlanStep> submittedTimeline = request.timeline() == null || request.timeline().isEmpty()
                ? draft.timeline()
                : request.timeline();
        List<String> lockedOrderIds = new ArrayList<>();
        List<String> failedOrders = new ArrayList<>();

        for (PlanStep step : submittedTimeline) {
            OrderIntent intent = intentFromSubmittedStep(draft.planId(), step, headcount);
            if (intent == null) continue;
            String targetTime = step.startTime() != null && !step.startTime().isBlank()
                    ? step.startTime()
                    : intent.targetTime();

            String orderId = executeOrderIntent(intent, headcount, targetTime);
            if (orderId == null) {
                failedOrders.add(intent.orderIntentId());
            } else {
                lockedOrderIds.add(orderId);
            }
        }

        String notificationText = request.notificationText() == null || request.notificationText().isBlank()
                ? draft.notificationText()
                : request.notificationText();
        ConfirmExecution execution = executeOrderGateway(lockedOrderIds, notificationText);
        List<PlanStep> updatedTimeline = submittedTimeline.stream()
                .map(step -> markExecuted(step, execution.status()))
                .toList();
        PlanStatus planStatus = failedOrders.isEmpty() && !lockedOrderIds.isEmpty() && !"FAILED".equalsIgnoreCase(execution.status())
                ? PlanStatus.CONFIRMED
                : lockedOrderIds.isEmpty() ? PlanStatus.FAILED : PlanStatus.PARTIALLY_BOOKED;
        executionStore.save(new PlanExecutionStore.DraftPlan(draft.planId(), draft.userId(), draft.intent(),
                updatedTimeline, draft.orderIntents(), notificationText, draft.version(), draft.previousVersionId(),
                planStatus, planStatus == PlanStatus.FAILED ? draft.lastConfirmedVersion() : draft.version(),
                request.idempotencyKey(), java.time.Instant.now()));

        return new ConfirmPlanResponse(execution.orderGroupId(), execution.status(),
                lockedOrderIds, failedOrders, notificationText, updatedTimeline, draft.version(), planStatus);
    }

    private AgentWorkflowEngine buildWorkflowEngine() {
        SessionStateStore sessionStateStore = new SessionStateStore(runtime);
        ContextAssembler contextAssembler = new ContextAssembler(executionStore, sessionStateStore);
        RouterRuleBook ruleBook = new RouterRuleBook();
        AgentRouter router = new AgentRouter((org.springframework.ai.chat.model.ChatModel) null, objectMapper, ruleBook);
        PlanPatchFactory patchFactory = new PlanPatchFactory(runtime);
        RenderTextService textService = new RenderTextService();
        CandidateCardService cardService = new CandidateCardService(replacementSearchEngine, patchFactory, runtime, textService);
        PlanDeltaExtractor deltaExtractor = planDeltaExtractor != null
                ? planDeltaExtractor
                : (planPatchExtractor == null ? null : new PlanDeltaExtractor(planPatchExtractor));
        return new AgentWorkflowEngine(fastPlanEngine, reactEngine, executionStore, intentExtractor,
                planPatchExtractor, deltaExtractor, planEditorEngine, replacementSearchEngine,
                contextAssembler, router, sessionStateStore, objectMapper, runtime, cardService, patchFactory, textService,
                null, null);
    }

    private CandidateCardService buildCandidateCardService() {
        PlanPatchFactory patchFactory = new PlanPatchFactory(runtime);
        return new CandidateCardService(replacementSearchEngine, patchFactory, runtime, new RenderTextService());
    }

    private void sendSse(SseEmitter emitter, SseEvent event) {
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.warn("[SSE] failed to send {}", event.type(), e);
        }
    }

    private void sendRawError(SseEmitter emitter, Exception error) {
        try {
            emitter.send(SseEmitter.event()
                    .name("ERROR")
                    .data("{\"type\":\"ERROR\",\"step\":0,\"content\":\"" + error.getMessage() + "\"}"));
        } catch (IOException ignored) {
        }
        emitter.completeWithError(error);
    }

    private boolean useReactMode() {
        return "react".equalsIgnoreCase(mode);
    }

    private String executeOrderIntent(OrderIntent intent, int headcount, String targetTime) {
        try {
            if ("BOOK_TICKET".equals(intent.type())) {
                ToolCallResult result = callTool("bookTickets", Map.of(
                        "poiId", intent.poiId(),
                        "num", headcount,
                        "sessionTime", targetTime));
                TicketResponse response = objectMapper.readValue(result.resultJson(), TicketResponse.class);
                return response.isSuccess() ? response.ticketId() : null;
            }
            if ("RESERVE_TABLE".equals(intent.type())) {
                ToolCallResult result = callTool("reserveRestaurant", Map.of(
                        "poiId", intent.poiId(),
                        "headcount", headcount,
                        "targetTime", targetTime));
                ReserveRestaurantResponse response = objectMapper.readValue(result.resultJson(), ReserveRestaurantResponse.class);
                return response.success() ? response.reservationId() : null;
            }
        } catch (Exception ignored) {
            return null;
        }
        return null;
    }

    private OrderIntent intentFromSubmittedStep(String planId, PlanStep step, int headcount) {
        if (step == null || step.isTransit() || step.poiId() == null || step.poiId().isBlank()) return null;
        String type = switch (step.phase()) {
            case "DINING", "DRINKS" -> "RESERVE_TABLE";
            case "ACTIVITY" -> "BOOK_TICKET";
            default -> "";
        };
        if (type.isBlank()) return null;
        String orderIntentId = step.orderIntentId() == null || step.orderIntentId().isBlank()
                ? "OI-" + planId + "-" + step.poiId()
                : step.orderIntentId();
        return new OrderIntent(orderIntentId, type, step.poiId(), step.poiName(), headcount, step.startTime(), "PENDING");
    }

    private ConfirmExecution executeOrderGateway(List<String> orderIds, String notificationText) {
        try {
            ToolCallResult result = callTool("executeOrderAndNotify", Map.of(
                    "orderIds", orderIds,
                    "contactToken", notificationText == null ? "user" : notificationText));
            ExecuteOrderResponse response = objectMapper.readValue(result.resultJson(), ExecuteOrderResponse.class);
            return new ConfirmExecution(response.orderGroupId(), response.status());
        } catch (Exception e) {
            return new ConfirmExecution("", "FAILED");
        }
    }

    private ToolCallResult callTool(String toolName, Map<String, Object> params) throws IOException {
        return toolRegistry.execute(toolName, objectMapper.writeValueAsString(params));
    }

    private PlanStep markExecuted(PlanStep step, String status) {
        boolean success = "DISPATCHED".equals(status);
        String bookingStatus = step.orderIntentId() == null || step.orderIntentId().isBlank()
                ? step.bookingStatus()
                : success ? "已下单" : "下单失败";
        String executionStatus = step.orderIntentId() == null || step.orderIntentId().isBlank()
                ? step.executionStatus()
                : success ? "EXECUTED" : "FAILED";
        return new PlanStep(step.durationMinutes(), step.startTime(), step.endTime(), step.phase(), step.action(),
                step.poiId(), step.poiName(), bookingStatus, step.note(), step.lnglat(), step.audience(),
                step.reason(), step.budget(), step.headcount(), step.constraints(), executionStatus, step.orderIntentId(),
                step.isTransit(), step.transportMode(), step.distanceKm(), step.fromPoiName(), step.toPoiName(),
                step.source(), step.address(), step.telephone(), step.businessHours(), step.typeCode(),
                step.segmentId());
    }

    private record ConfirmExecution(String orderGroupId, String status) {
    }
}
