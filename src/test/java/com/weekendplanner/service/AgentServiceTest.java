package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
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
}
