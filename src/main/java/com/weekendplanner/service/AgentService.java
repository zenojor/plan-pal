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
import com.weekendplanner.tool.ToolRegistry;
import com.weekendplanner.dto.ToolCallResult;
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

    @Value("${agent.mode:fast}")
    private String mode;

    public AgentService(FastPlanEngine fastPlanEngine,
                        ReActEngine reactEngine,
                        PlanExecutionStore executionStore,
                        ToolRegistry toolRegistry,
                        ObjectMapper objectMapper) {
        this.fastPlanEngine = fastPlanEngine;
        this.reactEngine = reactEngine;
        this.executionStore = executionStore;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
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
                plannerStreaming(request, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .name(event.type())
                                .data(objectMapper.writeValueAsString(event)));
                    } catch (IOException e) {
                        log.warn("[SSE] 发送失败: {}", e.getMessage());
                    }
                });
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

    private void plannerStreaming(PlanRequest request, java.util.function.Consumer<SseEvent> emitter) {
        if (useReactMode()) {
            reactEngine.executePlanStreaming(request, emitter);
        } else {
            fastPlanEngine.executePlanStreaming(request, emitter);
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
