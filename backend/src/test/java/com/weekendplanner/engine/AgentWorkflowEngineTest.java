package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.provider.SandboxMovieListingProvider;
import com.weekendplanner.tool.ActionExecutionTool;
import com.weekendplanner.tool.LocationExplorationTool;
import com.weekendplanner.tool.RestaurantBookingTool;
import com.weekendplanner.tool.RestaurantReservationTool;
import com.weekendplanner.tool.TicketingTool;
import com.weekendplanner.tool.ToolRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowEngineTest {

    @Test
    void replacementCandidateThenSecondReplyAppliesSelectedCandidateAndClearsPending() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest(
                "U201", "14:00到18:00，一个人，吃饭加活动"));
        PlanStep target = firstReplaceableStep(initial);
        String originalPoiId = target.poiId();

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "换一个", target.segmentId(),
                null, null, null, candidateEvents::add);

        SessionState withPending = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(withPending.pendingAction()).isNotNull();
        assertThat(withPending.lastCandidates()).isNotEmpty();
        assertThat(candidateEvents).anySatisfy(event -> assertThat(event.actionCard()).isNotNull());

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "第二个吧", null,
                null, null, null, selectionEvents::add);

        PlanExecutionStore.DraftPlan updated = fixture.store().find(initial.planId()).orElseThrow();
        PlanStep replaced = updated.timeline().stream()
                .filter(step -> target.segmentId().equals(step.segmentId()))
                .findFirst()
                .orElseThrow();
        assertThat(replaced.poiId()).isNotEqualTo(originalPoiId);
        PendingAction nextPending = fixture.sessionStateStore().find(initial.planId()).orElseThrow().pendingAction();
        if (nextPending != null) {
            assertThat(nextPending.candidateSetId()).isNotEqualTo(withPending.pendingAction().candidateSetId());
        }
        assertThat(selectionEvents.get(selectionEvents.size() - 1).type()).isEqualTo("FINISH");
    }

    @Test
    void extendingTimeCreatesAutomaticCandidateCardForOpenSlot() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest(
                "U202", "14:00到18:00，一个人，吃饭加活动"));

        List<SseEvent> events = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "延长到晚上十点",
                null, null, null, null, events::add);

        SseEvent finish = events.get(events.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().options()).isNotEmpty();
        assertThat(fixture.sessionStateStore().find(initial.planId()).orElseThrow().pendingAction()).isNotNull();
    }

    @Test
    void exploratoryInitialRequestReturnsActionCardWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U203", "第一次约会什么项目比较好"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("THOUGHT");
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.actionCard().options()).isNotEmpty();
        });
        assertThat(fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction()).isNotNull();
    }

    @Test
    void movieResearchSavesCandidatesAndSecondReplyCreatesTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> researchEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204", "帮我看看下午两点有什么电影"), researchEvents::add);

        assertThat(response.timeline()).isEmpty();
        SessionState state = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(state.lastCandidates()).isNotEmpty();
        assertThat(state.lastCandidates().get(0).type()).isEqualTo("MOVIE");

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "第二个吧",
                null, null, null, null, selectionEvents::add);

        SseEvent finish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(finish.timeline()).anySatisfy(step -> assertThat(step.action()).isEqualTo("Watch movie"));
    }

    private Fixture newFixture() {
        return newFixture(false);
    }

    private Fixture newFixtureWithResearch() {
        return newFixture(true);
    }

    private Fixture newFixture(boolean includeResearch) {
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
        FastPlanEngine fastPlanEngine = new FastPlanEngine(registry, intentExtractor, store, poiDatabase, objectMapper);
        ReflectionTestUtils.setField(fastPlanEngine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(fastPlanEngine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(fastPlanEngine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(fastPlanEngine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(fastPlanEngine, "maxChecksPerCategory", 3);
        ReplacementSearchEngine replacementSearchEngine = new ReplacementSearchEngine(poiDatabase);
        PlanPatchExtractor patchExtractor = new PlanPatchExtractor((ChatModel) null, objectMapper);
        PlanDeltaExtractor deltaExtractor = new PlanDeltaExtractor(patchExtractor);
        PlanEditorEngine editorEngine = new PlanEditorEngine(store, new TimelineAssembler(), replacementSearchEngine,
                registry, objectMapper);
        SessionStateStore sessionStateStore = new SessionStateStore();
        ContextAssembler contextAssembler = new ContextAssembler(store, sessionStateStore);
        AgentRouter router = new AgentRouter((ChatModel) null, objectMapper);
        ResearchRenderWorkflow researchRenderWorkflow = includeResearch
                ? new ResearchRenderWorkflow(intentExtractor, store, sessionStateStore, poiDatabase,
                new SandboxMovieListingProvider(), new AgentRuntimeProperties())
                : null;
        AgentWorkflowEngine workflow = new AgentWorkflowEngine(fastPlanEngine, null, store, intentExtractor,
                patchExtractor, deltaExtractor, editorEngine, replacementSearchEngine, contextAssembler, router,
                sessionStateStore, objectMapper, new AgentRuntimeProperties(), null, null, null,
                new InitialRequestRouter(), researchRenderWorkflow);
        return new Fixture(store, sessionStateStore, workflow);
    }

    private PlanStep firstReplaceableStep(PlanResponse response) {
        return response.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .orElseThrow();
    }

    private record Fixture(
            PlanExecutionStore store,
            SessionStateStore sessionStateStore,
            AgentWorkflowEngine workflow
    ) {
    }
}
