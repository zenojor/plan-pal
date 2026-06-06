package com.weekendplanner.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.ConfirmPlanRequest;
import com.weekendplanner.dto.ConfirmPlanResponse;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.workflow.FastPlanEngine;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.patch.PlanPatchExtractor;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.RideHailingTool;
import com.weekendplanner.tool.TicketingTool;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolRunner;
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
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);
        PlanExecutionStore store = new PlanExecutionStore();
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                runner,
                new IntentExtractor((ChatModel) null, objectMapper),
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, store, runner, objectMapper);
        PlanResponse draft = service.plan(new PlanRequest(
                "U006",
                "鎴戠幇鍦ㄦ櫄涓婂叓鐐瑰悗鎵嶆湁鏃堕棿锛屼竴涓汉鎯充竴鐩寸帺鍒板崄浜岀偣锛岀湅鐪嬫湁娌℃湁鍚冪殑鍜屽ソ鍠濈殑bar"));

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
            }
        });
    }

    @Test
    void confirmPlanExecutesTaxiTransitAsRideOrder() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper),
                new RideHailingTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);
        PlanExecutionStore store = new PlanExecutionStore();
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                runner,
                new IntentExtractor((ChatModel) null, objectMapper),
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, store, runner, objectMapper);
        PlanResponse draft = service.plan(new PlanRequest(
                "U010",
                "14:00-18:00, one person, dining and activity"));
        java.util.List<PlanStep> businessSteps = draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .limit(2)
                .toList();
        PlanStep from = businessSteps.get(0);
        PlanStep to = businessSteps.get(1);
        PlanStep taxi = new PlanStep(
                12,
                from.endTime(),
                "15:12",
                "TRANSIT",
                "Taxi 12 minutes",
                "RIDE-local",
                from.poiName() + " to " + to.poiName(),
                "Ride pending",
                "Mock ride-hailing order",
                to.lnglat(),
                "Transit",
                "User selected taxi in the map column.",
                "Taxi about CNY 18-26",
                draft.intent().headcount(),
                "",
                "PENDING",
                "RIDE-local",
                true,
                "TAXI",
                3.4,
                from.poiName(),
                to.poiName());
        java.util.List<PlanStep> submitted = new java.util.ArrayList<>(draft.timeline());
        submitted.add(1, taxi);

        ConfirmPlanResponse confirmed = service.confirmPlan(draft.planId(), new ConfirmPlanRequest(
                draft.planId(),
                draft.userId(),
                submitted,
                draft.intent().headcount(),
                draft.notificationText()));

        assertThat(confirmed.status()).isEqualTo("DISPATCHED");
        assertThat(confirmed.executedOrders()).anyMatch(id -> id.startsWith("RIDE-"));
        assertThat(confirmed.timeline()).anySatisfy(step -> {
            if ("TAXI".equals(step.transportMode())) {
                assertThat(step.executionStatus()).isEqualTo("EXECUTED");
            }
        });
    }

    @Test
    void planPatchAddPreservesOriginalPoisAndAddsDrinks() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);
        PlanExecutionStore store = new PlanExecutionStore();
        IntentExtractor intentExtractor = new IntentExtractor((ChatModel) null, objectMapper);
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                runner,
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
                "14:00鍒?8:00锛?涓汉锛屽悆楗姞娲诲姩"));

        String initialActivityPoiId = initialResponse.timeline().stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst().orElseThrow().poiId();
        String initialDiningPoiId = initialResponse.timeline().stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst().orElseThrow().poiId();

        PlanPatchExtractor patchExtractor = new PlanPatchExtractor((ChatModel) null, objectMapper);
        PlanEditorEngine editorEngine = new PlanEditorEngine(store, new TimelineAssembler(),
                new ReplacementSearchEngine(poiDatabase));
        PlanPatch patch = patchExtractor.extract("add one bar, keep dining", initialResponse.timeline(), initialResponse.intent());
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
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);
        PlanExecutionStore store = new PlanExecutionStore();
        IntentExtractor intentExtractor = new IntentExtractor((ChatModel) null, objectMapper);
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                runner,
                intentExtractor,
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        AgentService service = new AgentService(fastPlanEngine, store, runner, objectMapper, intentExtractor);
        PlanResponse initialResponse = service.plan(new PlanRequest(
                "U008",
                "14:00鍒?8:00锛?涓汉锛屽悆楗姞娲诲姩"));
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

    @Test
    void replacementCandidateCardReturnsPoiPreviewsWithoutChangingDraftVersion() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);
        PlanExecutionStore store = new PlanExecutionStore();
        IntentExtractor intentExtractor = new IntentExtractor((ChatModel) null, objectMapper);
        FastPlanEngine fastPlanEngine = new FastPlanEngine(
                runner,
                intentExtractor,
                store,
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);

        ReplacementSearchEngine replacementSearchEngine = new ReplacementSearchEngine(poiDatabase);
        PlanEditorEngine editorEngine = new PlanEditorEngine(store, new TimelineAssembler(),
                replacementSearchEngine);
        AgentService service = new AgentService(fastPlanEngine, store, runner, objectMapper,
                intentExtractor, new PlanPatchExtractor((ChatModel) null, objectMapper),
                editorEngine, replacementSearchEngine, null);
        PlanResponse initialResponse = service.plan(new PlanRequest(
                "U009",
                "14:00閸?8:00閿?娑擃亙姹夐敍灞芥倖妤楊厼濮炲ú璇插З"));
        PlanExecutionStore.DraftPlan draft = store.find(initialResponse.planId()).orElseThrow();
        PlanStep target = initialResponse.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .orElseThrow();
        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "REPLACE",
                new PlanPatch.Target(target.segmentId(), null, target.phase(), target.phase()),
                new PlanPatch.Requirements(java.util.List.of(), java.util.List.of(), java.util.List.of(),
                        null, null, null, false),
                true);

        ActionCard card = service.createReplacementCandidateCard(draft, patch);
        PlanExecutionStore.DraftPlan unchanged = store.find(initialResponse.planId()).orElseThrow();

        assertThat(unchanged.version()).isEqualTo(draft.version());
        assertThat(card.options()).hasSize(3);
        assertThat(card.options()).allSatisfy(option -> {
            assertThat(option.poiPreview()).isNotNull();
            assertThat(option.planPatch()).isNotNull();
            assertThat(option.planPatch().requirements().prefer()).anyMatch(value -> value.startsWith("SELECTED_POI:"));
        });
        java.util.List<String> usedPoiIds = initialResponse.timeline().stream()
                .map(PlanStep::poiId)
                .filter(id -> id != null && !id.isBlank())
                .toList();
        assertThat(card.options()).allSatisfy(option ->
                assertThat(usedPoiIds).doesNotContain(option.poiPreview().poiId()));
    }
}
