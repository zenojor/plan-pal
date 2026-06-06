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
import com.weekendplanner.engine.workflow.WorkflowActionService;
import com.weekendplanner.engine.graph.PlanPalGraphRuntime;
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
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolRunner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentWorkflowEngineTest {

    @Test
    void initialModelIdentityQuestionDoesNotStartPlanningWorkflow() {
        Fixture fixture = newFixture();
        List<SseEvent> events = new ArrayList<>();

        PlanResponse response = fixture.workflow().createPlanStreaming(
                new PlanRequest("U999", "\u4f60\u662f\u4ec0\u4e48\u6a21\u578b"),
                events::add);

        assertThat(response.executionStatus()).isEqualTo("CHAT_ONLY");
        assertThat(response.timeline()).isEmpty();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).type()).isEqualTo("FINISH");
        assertThat(events.get(0).actionCard()).isNull();
        assertThat(fixture.store().find(response.planId())).isEmpty();
    }

    @Test
    void replacementCandidateThenSecondReplyAppliesSelectedCandidateAndClearsPending() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest("U201", directPlanPrompt()));
        PlanStep target = firstReplaceableStep(initial);
        String originalPoiId = target.poiId();

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u6362\u4e00\u4e2a", target.segmentId(),
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
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u7b2c\u4e8c\u4e2a\u5427", null,
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
    void candidatePendingSemanticRefinementReturnsMatchingReplacementCard() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest("U232", directPlanPrompt()));
        PlanStep target = firstReplaceableStep(initial);

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "换一个", target.segmentId(),
                null, null, null, candidateEvents::add);
        SessionState beforeRefinement = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(beforeRefinement.pendingAction()).isNotNull();
        assertThat(beforeRefinement.pendingAction().type()).isEqualTo("SELECT_CANDIDATE");

        List<SseEvent> refinementEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "我想吃火锅",
                null, null, null, null, refinementEvents::add);

        SseEvent finish = refinementEvents.get(refinementEvents.size() - 1);
        assertThat(refinementEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("poi.search.replacement"));
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().options()).isNotEmpty();
        assertThat(finish.actionCard().options()).allSatisfy(option -> {
            assertThat(option.poiPreview()).isNotNull();
            assertThat(option.poiPreview().tags()).contains("hotpot");
        });
        SessionState afterRefinement = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(afterRefinement.pendingAction()).isNotNull();
        assertThat(afterRefinement.pendingAction().candidateSetId())
                .isNotEqualTo(beforeRefinement.pendingAction().candidateSetId());
    }

    @Test
    void candidatePendingQuestionAnswersWithoutConsumingPendingOrChangingTimeline() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest("U230", directPlanPrompt()));
        PlanStep target = firstReplaceableStep(initial);

        List<SseEvent> candidateEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u6362\u4e00\u4e2a", target.segmentId(),
                null, null, null, candidateEvents::add);
        SessionState pendingBefore = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        assertThat(pendingBefore.pendingAction()).isNotNull();

        List<SseEvent> qaEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u5403\u4e86\u5934\u5b62\u591a\u4e45\u80fd\u559d\u9152\uff1f",
                null, null, null, null, qaEvents::add);

        SseEvent finish = qaEvents.get(qaEvents.size() - 1);
        SessionState pendingAfter = fixture.sessionStateStore().find(initial.planId()).orElseThrow();
        PlanExecutionStore.DraftPlan draftAfter = fixture.store().find(initial.planId()).orElseThrow();
        assertThat(finish.content()).isNotBlank();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(pendingAfter.pendingAction()).isNotNull();
        assertThat(pendingAfter.pendingAction().candidateSetId()).isEqualTo(pendingBefore.pendingAction().candidateSetId());
        assertThat(draftAfter.timeline()).hasSize(initial.timeline().size());
    }

    @Test
    void extendingTimeCreatesAutomaticCandidateCardForOpenSlot() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest("U202", directPlanPrompt()));

        List<SseEvent> events = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u5ef6\u957f\u5230\u665a\u4e0a\u5341\u70b9",
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
                "U231", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), events::add);
        SessionState before = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(before.pendingAction()).isNotNull();
        assertThat(before.pendingAction().type()).isEqualTo("SELECT_PREFERENCE");

        List<SseEvent> qaEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u4e3a\u4ec0\u4e48\u7535\u5f71\u9002\u5408\u7b2c\u4e00\u6b21\u7ea6\u4f1a\uff1f",
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
                "U203", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), events::add);

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
    void completeFamilyFriendRequestReturnsPlanChoicesWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204", "\u5468\u516d\u4e0b\u5348\u5e26 5 \u5c81\u5b69\u5b50\u548c\u670b\u53cb\u5728\u672c\u5730\u73a9 4 \u5c0f\u65f6\uff0c\u522b\u592a\u8fdc\uff0c\u8981\u597d\u5403\u597d\u8d70\u3002"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(response.executionStatus()).isEqualTo("OPTIONS_READY");
        assertThat(response.variants()).isEmpty();
        assertThat(events).noneSatisfy(event -> assertThat(event.content()).contains("consult.respond"));
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("FINISH");
            assertThat(event.timeline()).isEmpty();
            assertThat(event.actionCard()).isNotNull();
            assertThat(event.actionCard().cardKind()).isEqualTo("PLAN_CHOICE");
            assertThat(event.actionCard().options()).hasSize(3);
        });
        PendingAction pending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(pending).isNotNull();
        assertThat(pending.type()).isEqualTo("PLAN_CHOICE");
    }

    @Test
    void coarseWeekendFamilyRequestAsksTimeInsteadOfDefaultingAfternoon() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204B", "\u4e00\u5bb6\u4e09\u53e3\u5468\u672b\u60f3\u8f7b\u677e\u5b89\u6392\u4e00\u4e0b\uff0c\u6700\u597d\u80fd\u5403\u996d\u3001\u6563\u6b65\u3001\u7ed9\u5b69\u5b50\u653e\u7535\u3002"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(response.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(response.intent().startTime()).isNull();
        assertThat(response.intent().endTime()).isNull();
        assertThat(events).noneSatisfy(event -> assertThat(event.type()).isEqualTo("PLAN_STEP"));
        assertThat(events).filteredOn(event -> "FINISH".equals(event.type()))
                .last()
                .satisfies(event -> {
                    assertThat(event.actionCard()).isNotNull();
                    assertThat(event.actionCard().cardKind()).isEqualTo("SLOT_COLLECTION");
                    assertThat(event.actionCard().options())
                            .filteredOn(option -> "SLOT_TIME_RANGE".equals(option.optionKind()))
                            .isNotEmpty();
                    assertThat(event.actionCard().options())
                            .filteredOn(option -> "SLOT_HEADCOUNT".equals(option.optionKind()))
                            .isEmpty();
                });
        PendingAction pending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(pending).isNotNull();
        assertThat(pending.type()).isEqualTo("INITIAL_PLAN_SLOT_FILLING");
    }

    @Test
    void initialSlotFillReturnsPlanChoicesWithoutBuildingTimeline() {
        Fixture fixture = newFixtureWithResearch();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204G", "\u4e00\u5bb6\u4e09\u53e3\u5468\u672b\u60f3\u8f7b\u677e\u5b89\u6392\u4e00\u4e0b\uff0c\u6700\u597d\u80fd\u5403\u996d\u3001\u6563\u6b65\u3001\u7ed9\u5b69\u5b50\u653e\u7535\u3002"), ignored -> {});

        List<SseEvent> slotEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u4e0b\u5348 14:00 \u5230 18:00",
                null, null, null, null, slotEvents::add);

        SseEvent finish = slotEvents.get(slotEvents.size() - 1);
        assertThat(slotEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("pending.workflow.resume: initial_plan_slot_filling"));
        assertThat(slotEvents).filteredOn(event -> event.actionCard() != null)
                .noneSatisfy(event -> assertThat(event.actionCard().cardKind()).isEqualTo("SLOT_COLLECTION"));
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.executionStatus()).isEqualTo("OPTIONS_READY");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().cardKind()).isEqualTo("PLAN_CHOICE");
        assertThat(finish.actionCard().options()).hasSize(3);
        PendingAction pending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(pending).isNotNull();
        assertThat(pending.type()).isEqualTo("PLAN_CHOICE");
    }

    @Test
    void selectedPlanBuildMarkerSkipsRouteChoiceLoop() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U205", directPlanPrompt()),
                events::add);

        assertThat(response.timeline()).isNotEmpty();
        assertThat(response.executionStatus()).isNotEqualTo("OPTIONS_READY");
        assertThat(events).noneSatisfy(event -> assertThat(event.content()).contains("plan.options"));
        assertThat(events.stream()
                .filter(event -> event.actionCard() != null)
                .map(event -> event.actionCard().cardKind()))
                .doesNotContain("PLAN_CHOICE");
    }

    @Test
    void preferenceSelectionUpdatesConstraintsWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U206", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), consultEvents::add);

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
                "U208", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), consultEvents::add);

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
                "U209", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), consultEvents::add);
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
                "U210", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), consultEvents::add);
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
                "U207", "\u7b2c\u4e00\u6b21\u7ea6\u4f1a\u4ec0\u4e48\u9879\u76ee\u6bd4\u8f83\u597d"), consultEvents::add);
        PlanPatch staleAddPatch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, "ACTIVITY", "ACTIVITY", null, null),
                new PlanPatch.Requirements(List.of(), List.of("MALL"),
                        List.of("SELECTED_POI:P001", "INDOOR"), null, null, null, false),
                false);

        List<SseEvent> events = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u52a0\u5165\u57ce\u5e02\u827a\u672f\u5c55\u89c8\u4e2d\u5fc3",
                null, "action-card:SUBMIT_PATCH", "add-poi-P001",
                new ObjectMapper().writeValueAsString(staleAddPatch), events::add);

        SseEvent finish = events.get(events.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.content()).isNotBlank();
        assertThat(finish.content()).isNotBlank();
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
                "U204", "\u5e2e\u6211\u770b\u770b\u4e0b\u5348\u4e24\u70b9\u6709\u4ec0\u4e48\u7535\u5f71"), researchEvents::add);

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
                    assertThat(event.actionCard().title()).isNotBlank();
                    assertThat(event.actionCard().options()).isNotEmpty();
                    assertThat(event.actionCard().options()).allSatisfy(option -> {
                        assertThat(option.optionKind()).isEqualTo("MOVIE_SCREENING");
                        assertThat(option.label()).isNotBlank();
                        assertThat(option.description()).contains("CNY");
                        assertThat(option.poiPreview()).isNotNull();
                    });
                });

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u9009\u7b2c\u4e00\u4e2a",
                null, null, null, null, selectionEvents::add);

        SseEvent finish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(selectionEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.select"))
                .anySatisfy(content -> assertThat(content).contains("plan.edit deferred"));
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.content()).isNotBlank();

        SessionState afterSelection = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(afterSelection.pendingAction()).isNotNull();
        assertThat(afterSelection.pendingAction().type()).isEqualTo("MOVIE_SCHEDULING");
        assertThat(afterSelection.pendingAction().workflowType()).isEqualTo("MOVIE");
        assertThat(afterSelection.pendingAction().selectedPatch()).isNotNull();
        assertThat(afterSelection.pendingAction().selectedLabel()).isNotBlank();

        List<SseEvent> slotEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u4e0b\u5348\u5427\u5c31\u9644\u8fd1",
                null, null, null, null, slotEvents::add);

        SseEvent slotFinish = slotEvents.get(slotEvents.size() - 1);
        SessionState afterSlotFill = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(slotFinish.type()).isEqualTo("FINISH");
        assertThat(slotFinish.timeline()).isEmpty();
        assertThat(slotFinish.content()).isNotBlank();
        assertThat(afterSlotFill.pendingAction()).isNotNull();
        assertThat(afterSlotFill.pendingAction().type()).isEqualTo("MOVIE_SCHEDULING");
        assertThat(afterSlotFill.pendingAction().collectedSlots())
                .containsEntry("timeRange", "AFTERNOON")
                .containsEntry("locationScope", "NEARBY")
                .doesNotContainKey("headcount");

        List<SseEvent> headcountEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u4e00\u4e2a",
                null, null, null, null, headcountEvents::add);

        SseEvent headcountFinish = headcountEvents.get(headcountEvents.size() - 1);
        assertThat(headcountEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("pending.workflow.resume: movie_schedule"));
        assertThat(headcountFinish.type()).isEqualTo("FINISH");
        assertThat(headcountFinish.timeline()).isNotEmpty();
        PlanStep movieStep = headcountFinish.timeline().stream()
                .filter(step -> !step.isTransit())
                .findFirst()
                .orElseThrow();
        assertThat(toMinutes(movieStep.startTime())).isBetween(14 * 60, 18 * 60);
        assertThat(fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction()).isNull();
    }

    private Fixture newFixture() {
        return newFixture(false);
    }

    private Fixture newFixtureWithResearch() {
        return newFixture(true);
    }

    private String directPlanPrompt() {
        return "[BUILD_SELECTED_PLAN] \u539f\u59cb\u9700\u6c42\uff1a14:00-18:00\uff0c2 \u4e2a\u4eba\uff0c\u5b89\u6392\u5403\u996d\u52a0\u6d3b\u52a8\uff0c\u522b\u592a\u8fdc\u3002"
                + "\u8bf7\u751f\u6210\u5305\u542b\u6d3b\u52a8\u548c\u9910\u5385\u7684\u884c\u7a0b\u62fc\u56fe\u3002";
    }

    private Fixture newFixture(boolean includeResearch) {
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
        PlanPatchExtractor patchExtractor = new PlanPatchExtractor((ChatModel) null, objectMapper);
        PlanDeltaExtractor deltaExtractor = new PlanDeltaExtractor(patchExtractor);
        PlanEditorEngine editorEngine = new PlanEditorEngine(store, new TimelineAssembler(), replacementSearchEngine);
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
        WorkflowActionService actions = new WorkflowActionService(
                fastPlanEngine, store, intentExtractor, patchExtractor, deltaExtractor, editorEngine,
                replacementSearchEngine, contextAssembler, router, sessionStateStore, objectMapper,
                new AgentRuntimeProperties(), null, null, null, new InitialRequestRouter(),
                researchRenderWorkflow, consultationWorkflow, null, null);
        PlanPalGraphRuntime graphRuntime = new PlanPalGraphRuntime(new com.weekendplanner.engine.graph.PlanGraphConfig(), new com.weekendplanner.engine.graph.PlanGraphNodes(actions), objectMapper);
        AgentWorkflowEngine workflow = new AgentWorkflowEngine(graphRuntime, actions);
        return new Fixture(store, sessionStateStore, workflow);
    }

    private PlanStep firstReplaceableStep(PlanResponse response) {
        return response.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .findFirst()
                .orElseThrow();
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private record Fixture(
            PlanExecutionStore store,
            SessionStateStore sessionStateStore,
            AgentWorkflowEngine workflow
    ) {
    }
}
