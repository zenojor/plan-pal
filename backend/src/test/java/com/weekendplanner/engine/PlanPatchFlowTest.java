package com.weekendplanner.engine;


import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.patch.PlanPatchExtractor;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.workflow.FastPlanEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.TicketingTool;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolRunner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlanPatchFlowTest {

    @Test
    void initialPlanAssignsStableSegmentIdsToEveryTimelineStep() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U099", "14:00到18:00，一个人，吃饭加活动"));

        assertThat(initial.timeline()).isNotEmpty();
        assertThat(initial.timeline()).allSatisfy(step -> assertThat(step.segmentId()).isNotBlank());
        assertThat(businessSteps(initial)).allSatisfy(step -> assertThat(step.segmentId()).startsWith("SEG-" + initial.planId()));
    }

    @Test
    void relaxPatchPreservesDiningAndReducesAfternoonLoad() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U100", "14:00到20:00，一个人，吃饭加活动"));
        String diningPoiId = firstPoi(initial, "DINING");
        long initialBusinessCount = businessSteps(initial).size();

        PlanPatch patch = fixture.patchExtractor.extract("太累了，下午少安排点，餐厅别换",
                initial.timeline(), initial.intent());
        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(patch.editType()).isEqualTo("RELAX");
        assertThat(patch.requirements().keep()).contains("DINING");
        assertThat(businessPoiIds(adjusted)).contains(diningPoiId);
        assertThat(businessSteps(adjusted).size()).isLessThanOrEqualTo((int) initialBusinessCount);
        assertThat(adjusted.timeline()).anySatisfy(step -> {
            if ("TRANSIT".equals(step.phase())) {
                assertThat(step.isTransit()).isTrue();
                assertThat(step.distanceKm()).isGreaterThan(0);
            }
        });
    }

    @Test
    void replacePatchSwapsOnlyTargetActivityAndKeepsDining() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U101", "14:00到18:00，一个人，吃饭加活动"));
        String diningPoiId = firstPoi(initial, "DINING");
        String activityPoiId = firstActivityPoi(initial);

        PlanPatch patch = fixture.patchExtractor.extract("把下午那个活动换成更适合小孩但别太远的地方",
                initial.timeline(), initial.intent());
        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(patch.editType()).isEqualTo("REPLACE");
        assertThat(patch.requiresSearch()).isTrue();
        assertThat(patch.requirements().prefer()).contains("CHILD_FRIENDLY", "NEARBY");
        assertThat(businessPoiIds(adjusted)).contains(diningPoiId);
        assertThat(firstActivityPoi(adjusted)).isNotEqualTo(activityPoiId);
    }

    @Test
    void replacePatchWithSegmentIdOnlyChangesTargetSegment() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U101B", "14:00到20:00，一个人，吃饭加活动"));
        PlanStep target = businessSteps(initial).stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .orElseThrow();
        String originalPoiId = target.poiId();
        String diningSegmentId = businessSteps(initial).stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst()
                .map(PlanStep::segmentId)
                .orElseThrow();

        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "REPLACE",
                new PlanPatch.Target(target.segmentId(), null, "ACTIVITY", "ACTIVITY"),
                new PlanPatch.Requirements(List.of(), List.of(), List.of("CHILD_FRIENDLY", "NEARBY"),
                        null, null, null, false),
                true);
        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        PlanStep replaced = businessSteps(adjusted).stream()
                .filter(step -> target.segmentId().equals(step.segmentId()))
                .findFirst()
                .orElseThrow();
        assertThat(replaced.poiId()).isNotEqualTo(originalPoiId);
        assertThat(businessSteps(adjusted)).anySatisfy(step -> {
            if (diningSegmentId.equals(step.segmentId())) {
                assertThat(step.phase()).isEqualTo("DINING");
            }
        });
    }

    @Test
    void deletePatchWithSegmentIdRemovesTargetSegment() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U101D", "14:00到20:00，一个人，吃饭加活动"));
        List<PlanStep> initialBusiness = businessSteps(initial);
        assertThat(initialBusiness).hasSizeGreaterThan(1);
        PlanStep target = initialBusiness.stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst()
                .orElse(initialBusiness.get(0));

        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "DELETE",
                new PlanPatch.Target(target.segmentId(), null, null, target.phase()),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                false);
        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(businessSteps(adjusted)).hasSize(initialBusiness.size() - 1);
        assertThat(businessSteps(adjusted)).noneMatch(step -> target.segmentId().equals(step.segmentId()));
    }

    @Test
    void selectedPoiPreferenceReplacesTargetWithExactPoi() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U101C", "14:00鍒?0:00锛屼竴涓汉锛屽悆楗姞娲诲姩"));
        PlanStep target = businessSteps(initial).stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .orElseThrow();
        java.util.Set<String> usedIds = new java.util.HashSet<>(businessPoiIds(initial));
        PlanPatch candidatePatch = new PlanPatch(
                "MODIFY_PLAN",
                "REPLACE",
                new PlanPatch.Target(target.segmentId(), null, target.phase(), target.phase()),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                true);
        String selectedPoiId = fixture.replacementSearchEngine
                .findCandidates(target.phase(), candidatePatch, initial.intent(), usedIds, 3)
                .get(0)
                .poiId();
        PlanPatch selectedPatch = new PlanPatch(
                "MODIFY_PLAN",
                "REPLACE",
                new PlanPatch.Target(target.segmentId(), null, target.phase(), target.phase()),
                new PlanPatch.Requirements(List.of(), List.of(), List.of("SELECTED_POI:" + selectedPoiId),
                        null, null, null, false),
                true);

        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), selectedPatch);

        PlanStep replaced = businessSteps(adjusted).stream()
                .filter(step -> target.segmentId().equals(step.segmentId()))
                .findFirst()
                .orElseThrow();
        assertThat(replaced.poiId()).isEqualTo(selectedPoiId);
    }

    @Test
    void endEarlierPatchKeepsDiningAndFinishesEarlier() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U102", "14:00到20:00，一个人，吃饭加活动"));
        String diningPoiId = firstPoi(initial, "DINING");
        int initialEnd = toMinutes(lastEnd(initial));

        PlanPatch patch = fixture.patchExtractor.extract("餐厅不要换，结束早点",
                initial.timeline(), initial.intent());
        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(patch.editType()).isIn("TIME_SHIFT", "RELAX");
        assertThat(patch.requirements().keep()).contains("DINING");
        assertThat(patch.requirements().endEarlier()).isTrue();
        assertThat(businessPoiIds(adjusted)).contains(diningPoiId);
        assertThat(toMinutes(lastEnd(adjusted))).isLessThan(initialEnd);
    }

    @Test
    void patchApplicationStoresNewDraftVersionWithPreviousVersionPointer() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U104", "14:00到18:00，一个人，吃饭加活动"));
        PlanExecutionStore.DraftPlan draft = fixture.store.find(initial.planId()).orElseThrow();
        PlanPatch patch = fixture.patchExtractor.extract("餐厅不要换，结束早点",
                initial.timeline(), initial.intent());

        fixture.editorEngine.applyPatch(draft, patch);
        PlanExecutionStore.DraftPlan updated = fixture.store.find(initial.planId()).orElseThrow();

        assertThat(updated.version()).isEqualTo(draft.version() + 1);
        assertThat(updated.previousVersionId()).isEqualTo(draft.versionId());
    }

    @Test
    void reorderPatchMovesTargetBeforeAnchorAndPersistsOrder() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U105", "14:00到18:00，一个人，吃饭加活动"));
        List<PlanStep> business = businessSteps(initial);
        assertThat(business).hasSizeGreaterThanOrEqualTo(2);

        PlanStep moved = business.get(1);
        PlanStep anchor = business.get(0);
        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "REORDER",
                new PlanPatch.Target(moved.segmentId(), null, null, null, anchor.segmentId(), "BEFORE"),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                false);

        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);
        List<PlanStep> adjustedBusiness = businessSteps(adjusted);

        assertThat(adjustedBusiness.get(0).segmentId()).isEqualTo(moved.segmentId());
        assertThat(adjustedBusiness.get(1).segmentId()).isEqualTo(anchor.segmentId());
    }

    @Test
    void reorderPatchCannotMoveMobilityBufferAwayFromTail() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U105B", "周六下午带 5 岁孩子和朋友在本地玩 4 个半小时，别太远，要好吃好走。"));
        PlanStep buffer = initial.timeline().stream()
                .filter(step -> "BUFFER".equals(step.executionStatus()))
                .findFirst()
                .orElseThrow();

        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "REORDER",
                new PlanPatch.Target(buffer.segmentId(), null, null, null, null, "START"),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                false);

        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(adjusted.timeline().get(0).executionStatus()).isNotEqualTo("BUFFER");
        assertThat(adjusted.timeline().get(adjusted.timeline().size() - 1).executionStatus()).isEqualTo("BUFFER");
    }

    @Test
    void addDrinksPatchAppendsEveningDrinksStep() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U106", "14:00到18:00，一个人，吃饭加活动"));
        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, "EVENING", "DRINKS", "DRINKS", null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                true);

        PlanResponse adjusted = fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), patch);

        assertThat(adjusted.timeline().stream().anyMatch(step -> "DRINKS".equals(step.phase()))).isTrue();
        assertThat(toMinutes(lastEnd(adjusted))).isGreaterThan(toMinutes(lastEnd(initial)));
    }

    @Test
    void eveningHotpotFeedbackBecomesDiningSearchPatch() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U106A", "14:00到18:00，一个人，吃饭加活动"));

        PlanPatch patch = fixture.patchExtractor.extract("晚上想吃火锅",
                initial.timeline(), initial.intent());
        java.util.Set<String> usedIds = new java.util.HashSet<>(businessPoiIds(initial));
        List<String> candidateIds = fixture.replacementSearchEngine
                .findCandidates("DINING", patch, initial.intent(), usedIds, 3)
                .stream()
                .map(poi -> poi.poiId())
                .toList();

        assertThat(patch.editType()).isEqualTo("ADD");
        assertThat(patch.target().timeRange()).isEqualTo("EVENING");
        assertThat(patch.target().phase()).isEqualTo("DINING");
        assertThat(patch.requirements().prefer()).contains("HOTPOT", "STRICT_TAGS");
        assertThat(patch.requiresSearch()).isTrue();
        assertThat(candidateIds).anyMatch(poiId -> poiId.equals("P015") || poiId.equals("P027")
                || poiId.equals("P062") || poiId.equals("P065"));
    }

    @Test
    void llmPatchKeepsExplicitCuisineEvidenceWhenModelOmitsTag() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(chatResponse("""
                {
                  "intent":"MODIFY_PLAN",
                  "editType":"ADD",
                  "target":{"timeRange":"EVENING","activityType":"ACTIVITY","phase":"ACTIVITY"},
                  "requirements":{"keep":[],"avoid":[],"prefer":[],"endEarlier":false},
                  "requiresSearch":true
                }
                """));
        PlanPatchExtractor extractor = new PlanPatchExtractor(chatModel, new ObjectMapper());

        PlanPatch patch = extractor.extract("晚上想吃火锅", List.of(), null);

        assertThat(patch.editType()).isEqualTo("ADD");
        assertThat(patch.target().timeRange()).isEqualTo("EVENING");
        assertThat(patch.target().phase()).isEqualTo("DINING");
        assertThat(patch.requirements().prefer()).contains("HOTPOT", "STRICT_TAGS");
    }

    @Test
    void cancelDrinksFeedbackBecomesDeletePatchAndRemovesDrinksNode() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U106B", "14:00到18:00，一个人，吃饭加活动"));
        PlanPatch addPatch = new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, "EVENING", "DRINKS", "DRINKS", null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"), null, null, null, false),
                true);
        fixture.editorEngine.applyPatch(fixture.store.find(initial.planId()).orElseThrow(), addPatch);
        PlanExecutionStore.DraftPlan withDrinks = fixture.store.find(initial.planId()).orElseThrow();

        assertThat(withDrinks.timeline().stream().anyMatch(step -> "DRINKS".equals(step.phase()))).isTrue();

        PlanPatch patch = fixture.patchExtractor.extract("算了不喝酒了吧",
                withDrinks.timeline(), withDrinks.intent());
        PlanResponse adjusted = fixture.editorEngine.applyPatch(withDrinks, patch);

        assertThat(patch.editType()).isEqualTo("DELETE");
        assertThat(patch.target().phase()).isEqualTo("DRINKS");
        assertThat(adjusted.timeline().stream().noneMatch(step -> "DRINKS".equals(step.phase()))).isTrue();
    }

    @Test
    void applyLockedCandidatePlanRespectsRequestedSegmentsAndBusinessHours() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U107", "晚上八点后，一个人，想吃特色小吃街和喝酒"));
        PlanExecutionStore.DraftPlan draft = fixture.store.find(initial.planId()).orElseThrow();

        PlanPatch selectedPatch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(null, null, "DINING", "DINING"),
                new PlanPatch.Requirements(List.of(), List.of(), List.of("SELECTED_POI:P016"), null, null, null, false),
                false);
        PendingAction pending = new PendingAction("PLAN_SLOT_FILLING", null, null, List.of("time", "duration", "headcount"),
                "DINING_LOCKED_PLAN", selectedPatch, "特色小吃街",
                List.of("startTime", "duration", "headcount", "orderPreference"),
                java.util.Map.of("startTime", "20:00",
                        "endTime", "24:00",
                        "headcount", 1,
                        "orderPreference", "DINING_THEN_ACTIVITY"),
                true);

        PlanResponse adjusted = fixture.editorEngine.applyLockedCandidatePlan(draft, pending);

        assertThat(adjusted.timeline().stream().anyMatch(step -> "DRINKS".equals(step.phase()))).isTrue();
        assertThat(adjusted.timeline().stream().noneMatch(step -> "P003".equals(step.poiId()))).isTrue();
    }

    @Test
    void sseEventCanCarryPlanPatchForFrontendExplanation() {
        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "RELAX",
                new PlanPatch.Target(null, "AFTERNOON", null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of(), "RELAXED", null, null, true),
                false);
        SseEvent event = new SseEvent("INTENT", 1, "放松节奏", List.of(),
                null, null, null, null, "P1", null, List.of(), "PENDING_CONFIRMATION", patch);

        assertThat(event.planPatch()).isEqualTo(patch);
    }

    @Test
    void deleteMallAndIndoorQuietFeedbackBecomesReplacePatch() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.fastPlanEngine.executePlan(new PlanRequest(
                "U103", "14:00到18:00，一个人，吃饭加活动"));

        PlanPatch patch = fixture.patchExtractor.extract("删掉商场，换个室内安静点的",
                initial.timeline(), initial.intent());

        assertThat(patch.editType()).isEqualTo("REPLACE");
        assertThat(patch.requirements().avoid()).contains("MALL");
        assertThat(patch.requirements().prefer()).contains("INDOOR", "QUIET");
    }

    private Fixture newFixture() {
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
        FastPlanEngine fastPlanEngine = new FastPlanEngine(runner, intentExtractor, store, poiDatabase, objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);
        ReplacementSearchEngine replacementSearchEngine = new ReplacementSearchEngine(poiDatabase);
        return new Fixture(store, fastPlanEngine, new PlanPatchExtractor((ChatModel) null, objectMapper),
                new PlanEditorEngine(store, new TimelineAssembler(), replacementSearchEngine),
                replacementSearchEngine);
    }

    private List<PlanStep> businessSteps(PlanResponse response) {
        return response.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .toList();
    }

    private List<String> businessPoiIds(PlanResponse response) {
        return businessSteps(response).stream().map(PlanStep::poiId).toList();
    }

    private String firstPoi(PlanResponse response, String phase) {
        return businessSteps(response).stream()
                .filter(step -> phase.equals(step.phase()))
                .findFirst()
                .map(PlanStep::poiId)
                .orElseThrow();
    }

    private String firstActivityPoi(PlanResponse response) {
        return businessSteps(response).stream()
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .map(PlanStep::poiId)
                .orElseThrow();
    }

    private String lastEnd(PlanResponse response) {
        return response.timeline().get(response.timeline().size() - 1).endTime();
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private record Fixture(
            PlanExecutionStore store,
            FastPlanEngine fastPlanEngine,
            PlanPatchExtractor patchExtractor,
            PlanEditorEngine editorEngine,
            ReplacementSearchEngine replacementSearchEngine
    ) {}
}
