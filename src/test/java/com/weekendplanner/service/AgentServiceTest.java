package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.FastPlanEngine;
import com.weekendplanner.engine.IntentExtractor;
import com.weekendplanner.engine.PlanEditorEngine;
import com.weekendplanner.engine.PlanExecutionStore;
import com.weekendplanner.engine.PlanPatchExtractor;
import com.weekendplanner.engine.ReplacementSearchEngine;
import com.weekendplanner.engine.TimelineAssembler;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.TicketingTool;
import com.weekendplanner.tool.ToolRegistry;
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
    void planPatchAddPreservesOriginalPoisAndAddsDrinks() {
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

        PlanResponse initialResponse = fastPlanEngine.executePlan(new PlanRequest(
                "U008",
                "14:00到18:00，2个人，吃饭加活动"));

        String initialActivityPoiId = initialResponse.timeline().stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst().orElseThrow().poiId();
        String initialDiningPoiId = initialResponse.timeline().stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst().orElseThrow().poiId();

        PlanPatchExtractor patchExtractor = new PlanPatchExtractor((ChatModel) null, objectMapper);
        PlanEditorEngine editorEngine = new PlanEditorEngine(store, new TimelineAssembler(),
                new ReplacementSearchEngine(poiDatabase), registry, objectMapper);
        PlanPatch patch = patchExtractor.extract("加一个bar，餐厅别换", initialResponse.timeline(), initialResponse.intent());
        PlanExecutionStore.DraftPlan draft = store.find(initialResponse.planId()).orElseThrow();
        PlanResponse adjustedResponse = editorEngine.applyPatch(draft, patch);

        java.util.List<String> adjustedPoiIds = adjustedResponse.timeline().stream()
                .map(PlanStep::poiId)
                .filter(id -> id != null && !id.isBlank())
                .toList();

        assertThat(adjustedPoiIds).contains(initialActivityPoiId, initialDiningPoiId);
        assertThat(patch.editType()).isEqualTo("ADD");
        assertThat(patch.requirements().keep()).contains("DINING");
        assertThat(adjustedResponse.timeline().stream().anyMatch(step -> "DRINKS".equals(step.phase()))).isTrue();
    }

    @Test
    void conflictActionCardComesFromBackendAndCarriesStructuredOptions() {
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
        PlanResponse initialResponse = service.plan(new PlanRequest(
                "U008",
                "14:00到18:00，1个人，吃饭加活动"));
        PlanExecutionStore.DraftPlan draft = store.find(initialResponse.planId()).orElseThrow();

        ActionCard actionCard = service.createConflictActionCard(draft);

        assertThat(actionCard).isNotNull();
        assertThat(actionCard.id()).isEqualTo("conflict-resolution");
        assertThat(actionCard.allowCustomInput()).isTrue();
        assertThat(actionCard.options()).isNotEmpty();
        assertThat(actionCard.options().get(0).id()).isEqualTo("extend-evening");
        assertThat(actionCard.options().get(0).planPatch()).isNotNull();
        assertThat(actionCard.options().get(0).planPatch().editType()).isEqualTo("ADD");
        assertThat(actionCard.options().get(0).planPatch().target().phase()).isEqualTo("DRINKS");
        assertThat(actionCard.options()).anySatisfy(option -> {
            if ("SUBMIT_PATCH".equals(option.actionType()) && option.planPatch() != null) {
                if ("REPLACE".equals(option.planPatch().editType())) {
                    assertThat(option.targetSegmentId()).isNotBlank();
                }
            }
        });
    }
}
