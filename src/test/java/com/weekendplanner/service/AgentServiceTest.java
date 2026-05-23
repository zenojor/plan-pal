package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.engine.FastPlanEngine;
import com.weekendplanner.engine.IntentExtractor;
import com.weekendplanner.engine.PlanExecutionStore;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AgentServiceTest {

    @Test
    void confirmPlanExecutesDraftOrderIntentsWithHeadcount() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolRegistry registry = new ToolRegistry(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        PlanExecutionStore store = new PlanExecutionStore();
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                registry,
                new IntentExtractor((ChatModel) null, objectMapper),
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, null, store, registry, objectMapper);
        PlanResponse draft = service.plan(new PlanRequest(
                "U006",
                "我现在晚上八点后才有时间，一个人想一直玩到十二点，看看有没有吃的和好喝的bar"));

        ConfirmPlanResponse confirmed = service.confirmPlan(draft.planId(), new ConfirmPlanRequest(
                draft.planId(),
                draft.userId(),
                draft.timeline(),
                draft.intent().headcount(),
                draft.notificationText()));

        assertThat(draft.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(draft.orderGroupId()).isBlank();
        assertThat(confirmed.status()).isEqualTo("DISPATCHED");
        assertThat(confirmed.orderGroupId()).startsWith("G");
        assertThat(confirmed.executedOrders()).hasSize(draft.orderIntents().size());
        assertThat(confirmed.timeline()).allSatisfy(step -> {
            if (step.orderIntentId() != null && !step.orderIntentId().isBlank()) {
                assertThat(step.headcount()).isEqualTo(1);
                assertThat(step.executionStatus()).isEqualTo("EXECUTED");
                assertThat(step.bookingStatus()).isEqualTo("已下单");
            }
        });
    }

    @Test
    void planChatStreamPreservesOriginalPoisAndAddsNewPoi() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolRegistry registry = new ToolRegistry(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        PlanExecutionStore store = new PlanExecutionStore();
        IntentExtractor intentExtractor = new IntentExtractor((ChatModel) null, objectMapper);
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                registry,
                intentExtractor,
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, null, store, registry, objectMapper, null, intentExtractor);

        // 1. Initial Plan (14:00 - 18:00, 1 person)
        PlanResponse initialResponse = service.plan(new PlanRequest(
                "U008",
                "14:00到18:00，1个人，吃饭加活动"));

        // Make sure it has original POIs
        assertThat(initialResponse.timeline()).filteredOn(step -> !step.isTransit()).hasSizeGreaterThanOrEqualTo(2);
        String initialActivityPoiId = initialResponse.timeline().stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst().get().poiId();
        String initialDiningPoiId = initialResponse.timeline().stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst().get().poiId();

        assertThat(initialActivityPoiId).isNotEmpty();
        assertThat(initialDiningPoiId).isNotEmpty();

        // 2. Simulate User Adjustment Chat
        String prompt = "晚上想去喝点酒";

        // Prepare context prompt similar to planChatStream
        StringBuilder sb = new StringBuilder();
        sb.append("当前已生成的行程拼图为：\n");
        for (PlanStep step : initialResponse.timeline()) {
            if (step.isTransit()) continue;
            sb.append("- ").append(step.startTime()).append("-").append(step.endTime())
              .append(" ").append(step.action()).append(" @ ").append(step.poiName());
            if (step.poiId() != null && !step.poiId().isBlank()) {
                sb.append(" (POI ID: ").append(step.poiId()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n用户对该行程提出的调整反馈或新意见是：\"").append(prompt).append("\"\n");

        PlanIntent mergedIntent = intentExtractor.mergeForAdjustment(initialResponse.intent(), prompt);
        PlanRequest newRequest = new PlanRequest("U008", sb.toString(), initialResponse.planId());

        java.util.List<SseEvent> events = new java.util.ArrayList<>();
        PlanResponse adjustedResponse = fastPlanEngine.executePlanStreaming(newRequest, events::add, mergedIntent);

        // Verify that BOTH initialActivityPoiId and initialDiningPoiId are STILL in the adjusted timeline
        java.util.List<String> adjustedPoiIds = adjustedResponse.timeline().stream()
                .map(PlanStep::poiId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        assertThat(adjustedPoiIds).contains(initialActivityPoiId, initialDiningPoiId);
        // Verify that a DRINKS slot is added
        assertThat(adjustedResponse.timeline().stream().anyMatch(step -> "DRINKS".equals(step.phase()))).isTrue();
    }

    @Test
    void planChatStreamDetectsConflictAndReturnsDecisionCard() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolRegistry registry = new ToolRegistry(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        PlanExecutionStore store = new PlanExecutionStore();
        IntentExtractor intentExtractor = new IntentExtractor((ChatModel) null, objectMapper);
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                registry,
                intentExtractor,
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, null, store, registry, objectMapper, null, intentExtractor);

        // 1. Initial Plan (14:00 - 18:00, 1 person)
        PlanResponse initialResponse = service.plan(new PlanRequest(
                "U008",
                "14:00到18:00，1个人，吃饭加活动"));

        // Save to store so planChatStream can retrieve it
        store.save(new PlanExecutionStore.DraftPlan(initialResponse.planId(), initialResponse.userId(),
                initialResponse.intent(), initialResponse.timeline(), initialResponse.orderIntents(), initialResponse.notificationText()));

        // 2. Call planChatStream with a conflict prompt: "晚上想去喝点酒"
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter = service.planChatStream(
                initialResponse.planId(), initialResponse.userId(), "晚上想去喝点酒");

        assertThat(emitter).isNotNull();
        // Give the async task a bit of time to complete
        Thread.sleep(300);
    }
}
