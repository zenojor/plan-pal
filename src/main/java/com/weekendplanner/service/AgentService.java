package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.dto.ToolCallResult;
import com.weekendplanner.engine.ConsultantEngine;
import com.weekendplanner.engine.FastPlanEngine;
import com.weekendplanner.engine.IntentExtractor;
import com.weekendplanner.engine.IntentValidator;
import com.weekendplanner.engine.PlanEditorEngine;
import com.weekendplanner.engine.PlanExecutionStore;
import com.weekendplanner.engine.PlanPatchExtractor;
import com.weekendplanner.engine.ReActEngine;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class AgentService {

    private static final Logger log = LoggerFactory.getLogger(AgentService.class);

    private final FastPlanEngine fastPlanEngine;
    private final ReActEngine reactEngine;
    private final PlanExecutionStore executionStore;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper;
    private final ConsultantEngine consultantEngine;
    private final IntentExtractor intentExtractor;
    private final PlanPatchExtractor planPatchExtractor;
    private final PlanEditorEngine planEditorEngine;
    private final IntentValidator intentValidator;

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
                        IntentValidator intentValidator) {
        this.fastPlanEngine = fastPlanEngine;
        this.reactEngine = reactEngine;
        this.executionStore = executionStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.consultantEngine = consultantEngine;
        this.intentExtractor = intentExtractor;
        this.planPatchExtractor = planPatchExtractor;
        this.planEditorEngine = planEditorEngine;
        this.intentValidator = intentValidator;
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
                consultantEngine, intentExtractor, null, null, intentValidator);
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
                null, null, null, null, new IntentValidator());
    }

    public PlanResponse plan(PlanRequest request) {
        log.info("[AgentService] plan userId={}, mode={}", request.userId(), mode);
        return useReactMode() ? reactEngine.executePlan(request) : fastPlanEngine.executePlan(request);
    }

    public SseEmitter planStream(PlanRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L);

        CompletableFuture.runAsync(() -> {
            try {
                PlanIntent intent = intentExtractor.extract(request.prompt());
                if (intent.isConsultingMode() && request.planId() == null) {
                    consultantEngine.executeConsultStream(request, event -> sendSse(emitter, event), intent);
                } else if (intentValidator.isMissingCriticalInfo(intent) && request.planId() == null) {
                    String planId = UUID.randomUUID().toString().substring(0, 8);
                    IntentValidator.MissingFields missing = intentValidator.detectMissingFields(intent);
                    String clarifyMessage = buildClarifyMessage(missing);
                    String finishMessage = buildClarifyFinishMessage(missing);

                    sendSse(emitter, new SseEvent("START", 0, "正在补齐关键信息...", List.of(),
                            null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                    sendSse(emitter, new SseEvent("THOUGHT", 1,
                            clarifyMessage,
                            List.of(), null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                    sendSse(emitter, new SseEvent("FINISH", 2,
                            finishMessage,
                            List.of(), "SUCCESS", "", "", null, planId, intent, List.of(), "PENDING_CONFIRMATION"));
                } else {
                    plannerStreaming(request, event -> sendSse(emitter, event), intent);
                }
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
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("未找到待调整的方案草案: " + planId));

        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            try {
                PlanPatch directPatch = parsePatchPayload(patchPayload)
                        .map(patch -> withSegmentId(patch, segmentId))
                        .orElse(null);

                if (shouldOfferConflictCard(draft, prompt, directPatch)) {
                    ActionCard card = createConflictActionCard(draft, prompt);
                    sendSse(emitter, new SseEvent("START", 0, "检测到当前修改会与既有时间窗口冲突。", draft.timeline(),
                            null, null, null, null, planId, draft.intent(), draft.orderIntents(),
                            "PENDING_CONFIRMATION", null, card));
                    sendSse(emitter, new SseEvent("INTENT", 1, "我整理了几种更稳妥的改法，您可以直接点选。", draft.timeline(),
                            null, null, null, null, planId, draft.intent(), draft.orderIntents(),
                            "PENDING_CONFIRMATION", null, card));
                    sendSse(emitter, new SseEvent("FINISH", 2, "选择一种处理方式，或直接补充您的修改要求。", draft.timeline(),
                            "SUCCESS", "", "", null, planId, draft.intent(), draft.orderIntents(),
                            "PENDING_CONFIRMATION", null, card));
                    emitter.complete();
                    return;
                }

                if (planPatchExtractor == null || planEditorEngine == null) {
                    PlanIntent mergedIntent = intentExtractor.mergeForAdjustment(draft.intent(), prompt);
                    plannerStreaming(new PlanRequest(userId, prompt, planId), event -> sendSse(emitter, event), mergedIntent);
                    emitter.complete();
                    return;
                }

                PlanPatch patch = directPatch != null
                        ? directPatch
                        : withSegmentId(planPatchExtractor.extract(prompt == null ? "" : prompt, draft.timeline(), draft.intent()), segmentId);
                String startContent = source == null || source.isBlank()
                        ? "PlanPatch 修改链路启动"
                        : "PlanPatch 修改链路启动: " + source;

                sendSse(emitter, new SseEvent("START", 0, startContent, draft.timeline(),
                        null, null, null, null, planId, draft.intent(), draft.orderIntents(),
                        "PENDING_CONFIRMATION", patch, null));

                sendSse(emitter, new SseEvent("INTENT", 1, planEditorEngine.describePatch(patch), draft.timeline(),
                        null, null, null, null, planId, draft.intent(), draft.orderIntents(),
                        "PENDING_CONFIRMATION", patch, null));

                PlanResponse response = planEditorEngine.applyPatch(draft, patch);
                sendSse(emitter, new SseEvent("PLAN_STEP", 2, "已完成局部修改并重排行程。", response.timeline(),
                        response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                        response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                        patch, null));
                sendSse(emitter, new SseEvent("FINISH", 3, response.summary(), response.timeline(),
                        response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                        response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                        patch, null));
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE-Chat] plan chat failed. planId={}, source={}, clientActionId={}", planId, source, clientActionId, e);
                sendRawError(emitter, e);
            }
        });
        return emitter;
    }

    ActionCard createConflictActionCard(PlanExecutionStore.DraftPlan draft) {
        return createConflictActionCard(draft, "");
    }

    ActionCard createConflictActionCard(PlanExecutionStore.DraftPlan draft, String prompt) {
        String lowerPrompt = prompt == null ? "" : prompt.toLowerCase();

        boolean isDining = !lowerPrompt.isBlank() && (lowerPrompt.contains("吃") || lowerPrompt.contains("餐") 
                || lowerPrompt.contains("饭") || lowerPrompt.contains("火锅") || lowerPrompt.contains("烧烤"));
        String phase = isDining ? "DINING" : "DRINKS";
        String typeCn = isDining ? "用餐" : "喝酒";

        List<ActionCard.ActionOption> options = new ArrayList<>();
        PlanPatch extendEveningPatch = new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, "EVENING", phase, phase, null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                true);
        options.add(new ActionCard.ActionOption(
                "extend-evening",
                "顺延到 21:00 并放到晚上",
                "保留白天安排，把新增" + typeCn + "放到晚间时段。",
                "SUBMIT_PATCH",
                null,
                null,
                extendEveningPatch));

        List<PlanStep> replaceable = draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.segmentId() != null && !step.segmentId().isBlank())
                .filter(step -> !"DINING".equalsIgnoreCase(step.phase()))
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .sorted(Comparator.comparingInt(PlanStep::durationMinutes).reversed())
                .limit(2)
                .toList();

        for (PlanStep step : replaceable) {
            PlanPatch patch = new PlanPatch(
                    "MODIFY_PLAN",
                    "REPLACE",
                    new PlanPatch.Target(step.segmentId(), null, phase, null, null, null),
                    new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                    true);
            options.add(new ActionCard.ActionOption(
                    "replace-" + step.segmentId(),
                    "把“" + step.poiName() + "”换成" + typeCn,
                    "保持当前结束时间，替换掉这个节点并重新接上路线。",
                    "SUBMIT_PATCH",
                    step.segmentId(),
                    null,
                    patch));
        }

        return new ActionCard(
                "conflict-resolution",
                "行程冲突决策方案",
                "当前计划到 18:00 已基本排满，新增晚间项目会挤占原有时间。",
                options,
                "直接说您的偏好，例如：餐厅别换，早点结束",
                true);
    }

    private boolean shouldOfferConflictCard(PlanExecutionStore.DraftPlan draft, String prompt, PlanPatch directPatch) {
        if (directPatch != null) return false;
        String lowerPrompt = prompt == null ? "" : prompt.toLowerCase();
        
        // 只要用户有晚上的餐饮、喝酒或活动倾向，且原行程在18:00之前结束，就触发冲突卡片
        boolean wantsEvening = lowerPrompt.contains("晚上") || lowerPrompt.contains("晚间")
                || lowerPrompt.contains("夜间") || lowerPrompt.contains("今晚");
        
        boolean wantsDiningOrDrinksOrActivity = lowerPrompt.contains("吃") || lowerPrompt.contains("饭")
                || lowerPrompt.contains("餐") || lowerPrompt.contains("火锅") || lowerPrompt.contains("烧烤")
                || lowerPrompt.contains("喝") || lowerPrompt.contains("酒吧") || lowerPrompt.contains("bar")
                || lowerPrompt.contains("玩") || lowerPrompt.contains("看") || lowerPrompt.contains("活动");
                
        boolean isOriginalAfternoonOnly = draft.intent().endTime().compareTo("18:00") <= 0;
        
        boolean isResolution = lowerPrompt.contains("顺延") || lowerPrompt.contains("去掉")
                || lowerPrompt.contains("替换") || lowerPrompt.contains("换成");
                
        return wantsEvening && wantsDiningOrDrinksOrActivity && isOriginalAfternoonOnly && !isResolution;
    }

    private Optional<PlanPatch> parsePatchPayload(String patchPayload) {
        if (patchPayload == null || patchPayload.isBlank()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(patchPayload, PlanPatch.class));
        } catch (IOException e) {
            throw new IllegalArgumentException("结构化修改指令解析失败", e);
        }
    }

    private PlanPatch withSegmentId(PlanPatch patch, String segmentId) {
        if (patch == null || segmentId == null || segmentId.isBlank()) return patch;
        if (patch.target().segmentId() != null && !patch.target().segmentId().isBlank()) return patch;
        return new PlanPatch(
                patch.intent(),
                patch.editType(),
                new PlanPatch.Target(segmentId, patch.target().timeRange(), patch.target().activityType(),
                        patch.target().phase(), patch.target().anchorSegmentId(), patch.target().position()),
                patch.requirements(),
                patch.requiresSearch());
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

    private void plannerStreaming(PlanRequest request, java.util.function.Consumer<SseEvent> emitter, PlanIntent intent) {
        if (useReactMode()) {
            reactEngine.executePlanStreaming(request, emitter, intent);
        } else {
            fastPlanEngine.executePlanStreaming(request, emitter, intent);
        }
    }

    private boolean useReactMode() {
        return "react".equalsIgnoreCase(mode);
    }

    public ConfirmPlanResponse confirmPlan(String planId, ConfirmPlanRequest request) {
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("未找到可执行方案: " + planId));

        int headcount = request.headcount() > 0 ? request.headcount() : draft.intent().headcount();
        List<PlanStep> submittedTimeline = request.timeline() == null || request.timeline().isEmpty()
                ? draft.timeline() : request.timeline();
        List<String> lockedOrderIds = new ArrayList<>();
        List<String> failedOrders = new ArrayList<>();

        for (PlanStep step : submittedTimeline) {
            OrderIntent intent = intentFromSubmittedStep(draft.planId(), step, headcount);
            if (intent == null) continue;
            String targetTime = step.startTime() != null && !step.startTime().isBlank()
                    ? step.startTime() : intent.targetTime();

            String orderId = executeOrderIntent(intent, headcount, targetTime);
            if (orderId == null) {
                failedOrders.add(intent.orderIntentId());
            } else {
                lockedOrderIds.add(orderId);
            }
        }

        String notificationText = request.notificationText() == null || request.notificationText().isBlank()
                ? draft.notificationText() : request.notificationText();
        ConfirmExecution execution = executeOrderGateway(lockedOrderIds, notificationText);
        List<PlanStep> updatedTimeline = submittedTimeline.stream()
                .map(step -> markExecuted(step, execution.status()))
                .toList();

        return new ConfirmPlanResponse(execution.orderGroupId(), execution.status(),
                lockedOrderIds, failedOrders, notificationText, updatedTimeline);
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
                    "contactToken", notificationText == null ? "用户" : notificationText));
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

    private String buildClarifyMessage(IntentValidator.MissingFields missing) {
        if (missing.missingTime() && missing.missingHeadcount()) {
            return "为了给您拼出更准确的行程，请补充出行时间段和人数。";
        } else if (missing.missingTime()) {
            return "为了给您拼出更准确的行程，请补充您的出行时间段（例如下午14:00-18:00）。";
        } else if (missing.missingHeadcount()) {
            return "为了给您拼出更准确的行程，请补充您的出行人数。";
        }
        return "为了给您拼出更准确的行程，请补充出行时间段和人数。";
    }

    private String buildClarifyFinishMessage(IntentValidator.MissingFields missing) {
        if (missing.missingTime() && missing.missingHeadcount()) {
            return "请提供时间范围和人数后，我再继续生成行程拼图。";
        } else if (missing.missingTime()) {
            return "请提供时间范围后，我再继续生成行程拼图。";
        } else if (missing.missingHeadcount()) {
            return "请提供人数后，我再继续生成行程拼图。";
        }
        return "请提供时间范围和人数后，我再继续生成行程拼图。";
    }

    private record ConfirmExecution(String orderGroupId, String status) {}
}
