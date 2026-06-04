package com.weekendplanner.engine;



import com.weekendplanner.engine.context.ContextAssembler;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.context.SessionStateStore;
import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.patch.PlanDeltaExtractor;
import com.weekendplanner.engine.patch.PlanEditorEngine;
import com.weekendplanner.engine.patch.PlanPatchExtractor;
import com.weekendplanner.engine.planning.PlanningAssumptionService;
import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.workflow.AgentWorkflowEngine;
import com.weekendplanner.engine.workflow.ConsultationWorkflow;
import com.weekendplanner.engine.workflow.ContextualResearchPlanner;
import com.weekendplanner.engine.workflow.FastPlanEngine;
import com.weekendplanner.engine.workflow.ResearchRenderWorkflow;
import com.weekendplanner.engine.routing.AgentRouter;
import com.weekendplanner.engine.routing.InitialRequestRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanPatch;
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
                "U201", "14:00-18:00，1个人，安排吃饭加活动"));
        PlanStep target = firstReplaceableStep(initial);
        String originalPoiId = target.poiId();

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "换一个", target.segmentId(),
                null, null, null, candidateEvents::add);

        SessionState withPending = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(withPending.pendingAction()).isNotNull();
        assertThat(withPending.lastCandidates()).isNotEmpty();
        assertThat(candidateEvents).anySatisfy(event -> assertThat(event.actionCard()).isNotNull());
        assertThat(candidateEvents).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("FINISH");
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.summary()).isNull();
        });

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
    void candidatePendingQuestionAnswersWithoutConsumingPendingOrChangingTimeline() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest(
                "U230", "14:00-18:00，1个人，安排吃饭加活动"));
        PlanStep target = firstReplaceableStep(initial);

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "换一个", target.segmentId(),
                null, null, null, candidateEvents::add);
        SessionState pendingBefore = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(pendingBefore.pendingAction()).isNotNull();

        List<SseEvent> qaEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "吃了头孢多久能喝酒？",
                null, null, null, null, qaEvents::add);

        SseEvent finish = qaEvents.get(qaEvents.size() - 1);
        SessionState pendingAfter = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        PlanExecutionStore.DraftPlan draftAfter = fixture.store().find(initial.planId()).orElseThrow();
        assertThat(finish.content()).contains("头孢", "酒");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(pendingAfter.pendingAction()).isNotNull();
        assertThat(pendingAfter.pendingAction().candidateSetId()).isEqualTo(pendingBefore.pendingAction().candidateSetId());
        assertThat(draftAfter.timeline()).hasSize(initial.timeline().size());
    }

    @Test
    void extendingTimeCreatesAutomaticCandidateCardForOpenSlot() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest(
                "U202", "14:00-18:00，1个人，安排吃饭加活动"));

        List<SseEvent> events = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "延长到晚上十点",
                null, null, null, null, events::add);

        SseEvent finish = events.get(events.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(finish.summary()).isNotBlank();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().options()).isNotEmpty();
        assertThat(fixture.sessionStateStore().find(initial.planId()).orElseThrow().pendingAction()).isNotNull();
    }

    @Test
    void preferencePendingQuestionAnswersWithoutSelectingPreference() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U231", "第一次约会什么项目比较好"), events::add);
        SessionState before = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(before.pendingAction()).isNotNull();
        assertThat(before.pendingAction().type()).isEqualTo("SELECT_PREFERENCE");

        List<SseEvent> qaEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "为什么电影适合第一次约会？",
                null, null, null, null, qaEvents::add);

        SseEvent finish = qaEvents.get(qaEvents.size() - 1);
        SessionState after = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().cardKind()).isEqualTo("PREFERENCE");
        assertThat(after.pendingAction()).isNotNull();
        assertThat(after.pendingAction().type()).isEqualTo("SELECT_PREFERENCE");
        assertThat(after.userConstraints()).isEqualTo(before.userConstraints());
    }

    @Test
    void exploratoryInitialRequestReturnsActionCardWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U203", "第一次约会什么项目比较好"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(events).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("consult.start"))
                .anySatisfy(content -> assertThat(content).contains("consult.respond"))
                .noneSatisfy(content -> assertThat(content).contains("poi.search"))
                .noneSatisfy(content -> assertThat(content).contains("candidate.rank"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("THOUGHT");
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.actionCard().options()).hasSizeBetween(2, 5);
            assertThat(event.actionCard().options()).allSatisfy(option -> {
                assertThat(option.actionType()).isEqualTo("SELECT_PREFERENCE");
                assertThat(option.poiPreview()).isNull();
                assertThat(option.planPatch()).isNull();
            });
        });
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("FINISH");
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.summary()).isNull();
        });
        assertThat(fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction()).isNotNull();
    }

    @Test
    void completeFamilyFriendRequestOffersPlanChoicesWithoutConsulting() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204", "周六下午带 5 岁孩子和朋友在本地玩 4 小时，别太远，要好吃好走。"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(response.executionStatus()).isEqualTo("OPTIONS_READY");
        assertThat(events).noneSatisfy(event -> assertThat(event.content()).contains("consult.respond"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("FINISH");
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.actionCard().title()).isEqualTo("选择一个方案来构建计划");
            assertThat(event.actionCard().options()).hasSize(3);
            assertThat(event.actionCard().options()).allSatisfy(option -> {
                assertThat(option.actionType()).isEqualTo("BUILD_PLAN");
                assertThat(option.poiIds()).isNotEmpty();
            });
        });
    }

    @Test
    void selectedPlanBuildMarkerSkipsRouteChoiceLoop() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U205", "[BUILD_SELECTED_PLAN] 原始需求：周六下午带 5 岁孩子和朋友在本地玩 4 小时。基于推荐的商家（商户ID: P008、P011）生成行程拼图。"),
                events::add);

        assertThat(response.timeline()).isNotEmpty();
        assertThat(response.executionStatus()).isNotEqualTo("OPTIONS_READY");
        assertThat(events).noneSatisfy(event -> assertThat(event.content()).contains("plan.options"));
        assertThat(events.stream()
                .filter(event -> event.actionCard() != null)
                .map(event -> event.actionCard().title()))
                .doesNotContain("选择一个方案来构建计划");
    }

    @Test
    void preferenceSelectionUpdatesConstraintsWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U206", "第一次约会什么项目比较好"), consultEvents::add);

        List<SseEvent> preferenceEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:relaxed_low_pressure",
                null, null, "action-card:SELECT_PREFERENCE", "pref-relaxed", preferenceEvents::add);

        SseEvent finish = preferenceEvents.get(preferenceEvents.size() - 1);
        SessionState state = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(state.userConstraints().experiencePreference().moods()).contains("relaxed");
        assertThat(state.userConstraints().experiencePreference().activityBiases()).contains("cafe");
        assertThat(state.pendingAction()).isNotNull();
        assertThat(state.pendingAction().type()).isEqualTo("ASK_CONTEXT");
    }

    @Test
    void contextAfterRitualPreferenceReturnsPreferenceAwareCandidates() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U208", "绗竴娆＄害浼氫粈涔堥」鐩瘮杈冨ソ"), consultEvents::add);

        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:ritual",
                null, null, "action-card:SELECT_PREFERENCE", "pref-ritual", ignored -> {});

        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u5927\u6982\u4e0b\u5348\uff0c\u5c31\u5728\u9644\u8fd1\u5427",
                null, null, null, null, contextEvents::add);

        SseEvent finish = contextEvents.get(contextEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.actionCard().options()).isNotEmpty();
        assertThat(finish.actionCard().options())
                .anySatisfy(option -> assertThat(option.poiPreview().tags())
                        .anyMatch(tag -> tag.contains("cocktail") || tag.contains("dessert")
                                || tag.contains("exhibition") || tag.contains("quiet_bar")));
        SessionState state = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(state.userConstraints().experiencePreference().timeHint()).isEqualTo("afternoon");
        assertThat(state.userConstraints().experiencePreference().locationHint()).isEqualTo("nearby");
        assertThat(state.pendingAction().type()).isEqualTo("SELECT_CANDIDATE");
    }

    @Test
    void contextualCandidateClickWithoutConcreteScheduleAsksForPlanningWindow() throws Exception {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U209", "绗竴娆＄害浼氫粈涔堥」鐩瘮杈冨ソ"), consultEvents::add);
        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:ritual",
                null, null, "action-card:SELECT_PREFERENCE", "pref-ritual", ignored -> {});

        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u5c31\u5728\u9644\u8fd1\u5427",
                null, null, null, null, contextEvents::add);

        SseEvent finish = contextEvents.get(contextEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.actionCard()).isNull();
        assertThat(finish.content()).isNotBlank();
        assertThat(fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction().type())
                .isEqualTo("ASK_CONTEXT");
    }

    @Test
    void contextualCandidateClickWithAssumedMorningWindowCreatesDraftTimeline() throws Exception {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U210", "绗竴娆＄害浼氫粈涔堥」鐩瘮杈冨ソ"), consultEvents::add);
        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:relaxed_low_pressure",
                null, null, "action-card:SELECT_PREFERENCE", "pref-relaxed", ignored -> {});

        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u4eca\u5929\u4e0a\u5348\u5f00\u59cb\u5427\uff0c\u5728\u9644\u8fd1",
                null, null, null, null, contextEvents::add);
        PlanPatch patch = contextEvents.get(contextEvents.size() - 1).actionCard().options().get(0).planPatch();

        List<SseEvent> applyEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u9009\u8fd9\u4e2a",
                null, "action-card:SUBMIT_PATCH", "contextual-choice",
                new ObjectMapper().writeValueAsString(patch), applyEvents::add);

        SseEvent finish = applyEvents.get(applyEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().startTime()).isEqualTo("10:00");
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().endTime()).isEqualTo("12:30");
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().headcount()).isEqualTo(2);
    }

    @Test
    void stalePoiPatchOnConsultingDraftDoesNotFailOrCreateTimeline() throws Exception {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U207", "第一次约会什么项目比较好"), consultEvents::add);
        PlanPatch staleAddPatch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, "ACTIVITY", "ACTIVITY", null, null),
                new PlanPatch.Requirements(List.of(), List.of("MALL"),
                        List.of("SELECTED_POI:P001", "INDOOR"), null, null, null, false),
                false);

        List<SseEvent> events = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "加入城市艺术展览中心",
                null, "action-card:SUBMIT_PATCH", "add-poi-P001",
                new ObjectMapper().writeValueAsString(staleAddPatch), events::add);

        SseEvent finish = events.get(events.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.content()).contains("我先记住你选的");
        assertThat(finish.content()).contains("时间、地点/范围和人数");
        assertThat(fixture.store().find(response.planId()).orElseThrow().timeline()).isEmpty();
        PendingAction pending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(pending).isNotNull();
        assertThat(pending.type()).isEqualTo("ASK_CONTEXT");
        assertThat(pending.workflowType()).isEqualTo("CONTEXTUAL_RESEARCH");
        assertThat(pending.selectedPatch()).isEqualTo(staleAddPatch);
        assertThat(pending.collectedSlots()).doesNotContainKeys("startTime", "headcount");
    }

    @Test
    void clarificationFinishKeepsSummaryEmpty() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U205", "\u5e2e\u6211\u5b89\u6392\u4e00\u4e2a\u5b8c\u6574\u884c\u7a0b"), events::add);

        SseEvent finish = events.get(events.size() - 1);
        assertThat(response.timeline()).isEmpty();
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(finish.summary()).isNull();
    }

    @Test
    void movieResearchSelectionCreatesMovieSchedulingPendingUntilSlotsAreFilled() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> researchEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204", "帮我看看下午两点有什么电影"), researchEvents::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(researchEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("router.decide"))
                .anySatisfy(content -> assertThat(content).contains("movie.search"))
                .anySatisfy(content -> assertThat(content).contains("candidate.rank"))
                .anySatisfy(content -> assertThat(content).contains("card.render"));
        SessionState state = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(state.lastCandidates()).isNotEmpty();
        assertThat(state.lastCandidates().get(0).type()).isEqualTo("MOVIE");
        assertThat(researchEvents)
                .filteredOn(event -> event.actionCard() != null)
                .anySatisfy(event -> {
                    assertThat(event.actionCard().cardKind()).isEqualTo("MOVIE_SCREENING");
                    assertThat(event.actionCard().title()).isEqualTo("选择电影场次");
                    assertThat(event.actionCard().options()).isNotEmpty();
                    assertThat(event.actionCard().options()).allSatisfy(option -> {
                        assertThat(option.optionKind()).isEqualTo("MOVIE_SCREENING");
                        assertThat(option.label()).isNotBlank();
                        assertThat(option.description()).contains("分钟", "评分", "CNY");
                        assertThat(option.poiPreview()).isNotNull();
                    });
                });

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "第二个吧",
                null, null, null, null, selectionEvents::add);

        SseEvent finish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(selectionEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.select"))
                .anySatisfy(content -> assertThat(content).contains("plan.edit deferred"));
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.content()).contains("我先记住你选的");

        SessionState afterSelection = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(afterSelection.pendingAction()).isNotNull();
        assertThat(afterSelection.pendingAction().type()).isEqualTo("MOVIE_SCHEDULING");
        assertThat(afterSelection.pendingAction().workflowType()).isEqualTo("MOVIE");
        assertThat(afterSelection.pendingAction().selectedPatch()).isNotNull();
        assertThat(afterSelection.pendingAction().selectedLabel()).contains("功夫熊猫5");

        List<SseEvent> slotEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "下午吧就附近",
                null, null, null, null, slotEvents::add);

        SseEvent slotFinish = slotEvents.get(slotEvents.size() - 1);
        SessionState afterSlotFill = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(slotFinish.type()).isEqualTo("FINISH");
        assertThat(slotFinish.timeline()).isEmpty();
        assertThat(slotFinish.content()).contains("还差人数");
        assertThat(afterSlotFill.pendingAction()).isNotNull();
        assertThat(afterSlotFill.pendingAction().type()).isEqualTo("MOVIE_SCHEDULING");
        assertThat(afterSlotFill.pendingAction().collectedSlots())
                .containsEntry("timeRange", "AFTERNOON")
                .containsEntry("locationScope", "NEARBY")
                .doesNotContainKey("headcount");
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
        ConsultationWorkflow consultationWorkflow = new ConsultationWorkflow(null, intentExtractor, store,
                sessionStateStore, objectMapper, poiDatabase, new ContextualResearchPlanner(),
                new PlanningAssumptionService(), new AgentRuntimeProperties());
        AgentWorkflowEngine workflow = new AgentWorkflowEngine(fastPlanEngine, store, intentExtractor,
                patchExtractor, deltaExtractor, editorEngine, replacementSearchEngine, contextAssembler, router,
                sessionStateStore, objectMapper, new AgentRuntimeProperties(), null, null, null,
                new InitialRequestRouter(), researchRenderWorkflow, consultationWorkflow);
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
