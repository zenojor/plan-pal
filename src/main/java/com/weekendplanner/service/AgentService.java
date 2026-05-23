package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.ExecuteOrderResponse;
import com.weekendplanner.dto.OrderIntent;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.ReserveRestaurantResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.dto.TicketResponse;
import com.weekendplanner.engine.FastPlanEngine;
import com.weekendplanner.engine.PlanExecutionStore;
import com.weekendplanner.engine.ReActEngine;
import com.weekendplanner.engine.ConsultantEngine;
import com.weekendplanner.engine.IntentExtractor;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.tool.ToolRegistry;
import com.weekendplanner.dto.ToolCallResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
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
    private final ConsultantEngine consultantEngine;
    private final IntentExtractor intentExtractor;

    @Value("${agent.mode:fast}")
    private String mode;

    @Autowired
    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper,
                        ConsultantEngine consultantEngine,
                        IntentExtractor intentExtractor) {
        this.fastPlanEngine = fastPlanEngine;
        this.reactEngine = reactEngine;
        this.executionStore = executionStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
        this.consultantEngine = consultantEngine;
        this.intentExtractor = intentExtractor;
    }

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper) {
        this(fastPlanEngine, reactEngine, executionStore, toolRegistry, objectMapper, null, null);
    }

    /** 同步规划 */
    public PlanResponse plan(PlanRequest request) {
        log.info("[AgentService] plan userId={}, mode={}", request.userId(), mode);
        return useReactMode() ? reactEngine.executePlan(request) : fastPlanEngine.executePlan(request);
    }

    /**
     * SSE 流式规划 — 真正的逐步骤实时推送
     *
     * 引擎在每一步完成时通过 Consumer 回调发射事件，
     * SseEmitter 立即发送到前端，不等待整个循环结束。
     */
    public SseEmitter planStream(PlanRequest request) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        CompletableFuture.runAsync(() -> {
            try {
                PlanIntent intent = intentExtractor.extract(request.prompt());
                if (intent.isConsultingMode() && request.planId() == null) {
                    consultantEngine.executeConsultStream(request, event -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(event.type())
                                    .data(objectMapper.writeValueAsString(event)));
                        } catch (IOException e) {
                            log.warn("[SSE-Consult] 发送失败: {}", e.getMessage());
                        }
                    }, intent);
                } else if (intent.isMissingCriticalPlanningInfo() && request.planId() == null) {
                    // 关键参数缺失自检与主动追问拦截
                    String planId = java.util.UUID.randomUUID().toString().substring(0, 8);
                    try {
                        SseEvent startEvent = new SseEvent("START", 0, "🔍 检测到规划因子缺失，正在向您追问补充...", List.of(),
                                null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION");
                        emitter.send(SseEmitter.event().name("START").data(objectMapper.writeValueAsString(startEvent)));

                        String clarificationContent = "为了能为您拼合出最精准的行程拼图，请问您计划在**什么时间段**出行（例如：下午两点到六点），以及**总共几个人**呢？期待您的补充，我随时为您一键合成！";
                        SseEvent thoughtEvent = new SseEvent("THOUGHT", 1, clarificationContent, List.of(),
                                null, null, null, null, planId, intent, List.of(), "PENDING_CONFIRMATION");
                        emitter.send(SseEmitter.event().name("THOUGHT").data(objectMapper.writeValueAsString(thoughtEvent)));

                        SseEvent finishEvent = new SseEvent("FINISH", 2, "请提供出行的时间范围与人数以完成行程拼图合成。", List.of(),
                                "SUCCESS", "", "", null, planId, intent, List.of(), "PENDING_CONFIRMATION");
                        emitter.send(SseEmitter.event().name("FINISH").data(objectMapper.writeValueAsString(finishEvent)));
                    } catch (IOException e) {
                        log.warn("[SSE-Clarify] 发送失败: {}", e.getMessage());
                    }
                } else {
                    plannerStreaming(request, event -> {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name(event.type())
                                    .data(objectMapper.writeValueAsString(event)));
                        } catch (IOException e) {
                            log.warn("[SSE] 发送失败: {}", e.getMessage());
                        }
                    }, intent);
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE] 规划异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("ERROR")
                            .data("{\"type\":\"ERROR\",\"step\":0,\"content\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * SSE 对话式规划微调 —— 从用户的纯调整文本提取增量意图，与原始 intent 智能合并。
     */
    public SseEmitter planChatStream(String planId, String userId, String prompt) {
        PlanExecutionStore.DraftPlan draft = executionStore.find(planId)
                .orElseThrow(() -> new IllegalArgumentException("未找到待调整的方案草案: " + planId));

        // 1. 检测时间超限与容量排满冲突（晚上喝酒微调，且原方案仅限下午 18:00 结束）
        String lowerPrompt = prompt.toLowerCase();
        boolean wantsDrinks = lowerPrompt.contains("喝点酒") || lowerPrompt.contains("喝酒") || lowerPrompt.contains("酒吧") || lowerPrompt.contains("bar");
        boolean wantsEvening = lowerPrompt.contains("晚上") || lowerPrompt.contains("晚间") || lowerPrompt.contains("夜间") || lowerPrompt.contains("今晚");
        boolean isOriginalAfternoonOnly = draft.intent().endTime().compareTo("18:00") <= 0;
        boolean isResolution = lowerPrompt.contains("顺延") || lowerPrompt.contains("去掉") || lowerPrompt.contains("替换") || lowerPrompt.contains("换成");

        if (wantsDrinks && wantsEvening && isOriginalAfternoonOnly && !isResolution) {
            SseEmitter emitter = new SseEmitter(300_000L);
            CompletableFuture.runAsync(() -> {
                try {
                    String explanationText = "检测到您想在晚上增加喝酒安排，但当前行程时间（" 
                            + draft.intent().startTime() + "-" + draft.intent().endTime() + "）已排满。请问您希望如何调整？";
                    
                    SseEvent startEvent = new SseEvent("START", 0, "🔍 检测到行程冲突，正在生成可选择的微调方案...", draft.timeline(),
                            null, null, null, null, planId, draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION");
                    emitter.send(SseEmitter.event().name("START").data(objectMapper.writeValueAsString(startEvent)));

                    SseEvent thoughtEvent = new SseEvent("INTENT", 1, explanationText, draft.timeline(),
                            null, null, null, null, planId, draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION");
                    emitter.send(SseEmitter.event().name("INTENT").data(objectMapper.writeValueAsString(thoughtEvent)));

                    SseEvent finishEvent = new SseEvent("FINISH", 2, "请选择适合您的微调调整选项以继续。", draft.timeline(),
                            "SUCCESS", "", "", null, planId, draft.intent(), draft.orderIntents(), "PENDING_CONFIRMATION");
                    emitter.send(SseEmitter.event().name("FINISH").data(objectMapper.writeValueAsString(finishEvent)));
                    
                    emitter.complete();
                } catch (IOException e) {
                    log.warn("[SSE-Conflict-Clarify] 发送失败: {}", e.getMessage());
                    emitter.completeWithError(e);
                }
            });
            return emitter;
        }

        // 2. 从用户的纯调整文本中提取增量意图，与原始 intent 智能合并
        PlanIntent mergedIntent = intentExtractor.mergeForAdjustment(draft.intent(), prompt);

        // 2. 构造带上下文的完整 prompt 供引擎使用
        StringBuilder sb = new StringBuilder();
        sb.append("当前已生成的行程拼图为：\n");
        for (PlanStep step : draft.timeline()) {
            if (step.isTransit()) continue;
            sb.append("- ").append(step.startTime()).append("-").append(step.endTime())
              .append(" ").append(step.action()).append(" @ ").append(step.poiName());
            if (step.poiId() != null && !step.poiId().isBlank()) {
                sb.append(" (POI ID: ").append(step.poiId()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n用户对该行程提出的调整反馈或新意见是：\"").append(prompt).append("\"\n");
        sb.append("请基于上述当前方案及反馈，进行合理的局部重选和修改，未受影响的段落应尽量保留，并重新规划和衔接好全部交通及时间线。");

        PlanRequest newRequest = new PlanRequest(userId, sb.toString(), planId);

        // 3. 直接创建 SSE 发射器，传递正确合并的 intent（绕过 planStream 避免重新提取）
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            try {
                plannerStreaming(newRequest, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type())
                                .data(objectMapper.writeValueAsString(event)));
                    } catch (IOException e) {
                        log.warn("[SSE-Chat] 发送失败: {}", e.getMessage());
                    }
                }, mergedIntent);
                emitter.complete();
            } catch (Exception e) {
                log.error("[SSE-Chat] 规划异常", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("ERROR")
                            .data("{\"type\":\"ERROR\",\"step\":0,\"content\":\"" + e.getMessage() + "\"}"));
                } catch (IOException ignored) {}
                emitter.completeWithError(e);
            }
        });
        return emitter;
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
                step.isTransit(), step.transportMode(), step.distanceKm(), step.fromPoiName(), step.toPoiName());
    }

    private record ConfirmExecution(String orderGroupId, String status) {}
}
