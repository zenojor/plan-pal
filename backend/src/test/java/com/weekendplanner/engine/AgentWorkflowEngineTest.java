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
import com.weekendplanner.dto.ActionCard;
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
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void selectingAutoRecommendedCandidateDoesNotChainAnotherCandidateCard() {
        Fixture fixture = newFixture();
        PlanResponse initial = fixture.workflow().createPlan(new PlanRequest("U202B", directPlanPrompt()));

        List<SseEvent> autoEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u5ef6\u957f\u5230\u665a\u4e0a\u5341\u70b9",
                null, null, null, null, autoEvents::add);
        assertThat(autoEvents.get(autoEvents.size() - 1).actionCard()).isNotNull();
        assertThat(fixture.sessionStateStore().find(initial.planId()).orElseThrow().pendingAction()).isNotNull();

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(initial.planId(), initial.userId(), "\u9009\u7b2c\u4e00\u4e2a",
                null, null, null, null, selectionEvents::add);

        SseEvent finish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(finish.actionCard()).isNull();
        assertThat(fixture.sessionStateStore().find(initial.planId()).orElseThrow().pendingAction()).isNull();
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
    void concreteDrinksRequestReturnsUserConstrainedPlanChoices() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204D", "今天 14:00 到 20:30，3 个人，安排吃饭和轻活动，按我补充的商圈或地点范围安排，节奏轻松，少排队少折腾，室内，几个朋友一起玩，晚上要喝酒。"),
                events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(response.executionStatus()).isEqualTo("OPTIONS_READY");
        SseEvent finish = events.stream().filter(event -> "FINISH".equals(event.type())).reduce((a, b) -> b).orElseThrow();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().cardKind()).isEqualTo("PLAN_CHOICE");
        assertThat(finish.actionCard().options()).hasSize(3);
        assertThat(finish.actionCard().options()).allSatisfy(option -> {
            assertThat(option.label()).startsWith("方案 ");
            assertThat(option.description()).contains("实际匹配到");
            assertThat(option.poiIds()).hasSizeGreaterThanOrEqualTo(3);
        });
    }

    @Test
    void concreteMovieRequestReturnsCinemaPlanChoices() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204C", "今天 14:00 到 18:00，2 个人，安排吃饭和轻活动，优先附近少绕路，节奏轻松，少排队少折腾，我要看电影。"),
                events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(response.executionStatus()).isEqualTo("OPTIONS_READY");
        SseEvent finish = events.stream().filter(event -> "FINISH".equals(event.type())).reduce((a, b) -> b).orElseThrow();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().cardKind()).isEqualTo("PLAN_CHOICE");
        assertThat(finish.actionCard().options()).hasSize(3);
        assertThat(finish.actionCard().options()).allSatisfy(option -> {
            assertThat(option.label()).startsWith("方案 ");
            assertThat(option.description()).contains("实际匹配到");
            assertThat(option.poiIds()).anySatisfy(poiId -> assertThat(poiId).isIn("P030", "P031", "P032", "P033", "P034", "P035", "P036", "P037", "P066", "P067", "P068", "P069"));
        });
    }

    @Test
    void planChoiceUsesChatModelWhenAvailable() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse("""
                {"choices":[
                  {"title":"LLM movie first","description":"LLM keeps cinema, dining and nearby constraints.",
                   "segments":[
                     {"phase":"CINEMA","tags":["MOVIE","INDOOR","NEARBY"]},
                     {"phase":"DINING","tags":["SOCIAL_DINING","NEARBY"]}
                   ]},
                  {"title":"LLM dinner first","description":"LLM starts with food, then keeps the cinema stop close.",
                   "segments":[
                     {"phase":"DINING","tags":["SOCIAL_DINING","NEARBY"]},
                     {"phase":"CINEMA","tags":["MOVIE","INDOOR","NEARBY"]}
                   ]},
                  {"title":"LLM compact extra","description":"LLM makes the route compact and adds a light activity.",
                   "segments":[
                     {"phase":"LEISURE","tags":["NEARBY","SOCIAL_ENTERTAINMENT"]},
                     {"phase":"DINING","tags":["SOCIAL_DINING","NEARBY"]},
                     {"phase":"CINEMA","tags":["MOVIE","INDOOR","NEARBY"]}
                   ]}
                ]}
                """));
        Fixture fixture = newFixtureWithResearch(chatModel);

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204L", "\u4eca\u5929 14:00 \u5230 22:00\uff0c2 \u4e2a\u4eba\uff0c\u5b89\u6392\u5403\u996d\u548c\u8f7b\u6d3b\u52a8\uff0c\u4f18\u5148\u9644\u8fd1\u5c11\u7ed5\u8def\uff0c\u53ef\u4ee5\u66f4\u7d27\u51d1\uff0c\u591a\u5b89\u6392\u4e00\u4e2a\u70b9\uff0c\u6211\u8981\u770b\u7535\u5f71\u3002"),
                events::add);

        assertThat(response.executionStatus()).isEqualTo("OPTIONS_READY");
        SseEvent finish = events.stream().filter(event -> "FINISH".equals(event.type())).reduce((a, b) -> b).orElseThrow();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().cardKind()).isEqualTo("PLAN_CHOICE");
        assertThat(finish.actionCard().options()).extracting(ActionCard.ActionOption::label)
                .allSatisfy(label -> assertThat(label).startsWith("方案 "))
                .noneSatisfy(label -> assertThat(label).contains("LLM "));
        assertThat(finish.actionCard().options()).extracting(ActionCard.ActionOption::description)
                .allSatisfy(description -> assertThat(description).contains("实际匹配到"));
        assertThat(events).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("plan.options.source: LLM"));
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void homepageMoviePromptReturnsMovieCardWithoutTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204M", "最近有没有什么好看的电影"), events::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(events).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("movie.search"));
        assertThat(events)
                .filteredOn(event -> event.actionCard() != null)
                .last()
                .satisfies(event -> {
                    assertThat(event.timeline()).isEmpty();
                    assertThat(event.actionCard().cardKind()).isEqualTo("MOVIE_SCREENING");
                    assertThat(event.actionCard().options()).isNotEmpty();
                    assertThat(event.actionCard().options())
                            .allSatisfy(option -> assertThat(option.optionKind()).isEqualTo("MOVIE_SCREENING"));
                });
        PendingAction pending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(pending).isNotNull();
        assertThat(pending.type()).isEqualTo("SELECT_CANDIDATE");
        assertThat(pending.workflowType()).isEqualTo("MOVIE");
    }

    @Test
    void homepageMovieSelectionCreatesMovieAndOptionalBufferSlot() {
        Fixture fixture = newFixtureWithResearch();

        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204N", "最近有没有什么好看的电影"), event -> {});

        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选第一个",
                null, null, null, null, selectionEvents::add);

        SseEvent finish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
            assertThat(step.reason()).contains("Selected screening");
            assertThat(step.poiId()).isNotBlank();
        });
        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.executionStatus()).isEqualTo("BUFFER");
            assertThat(step.phase()).isEqualTo("LEISURE");
            assertThat(step.poiName()).isEqualTo("预留机动时间");
            assertThat(step.poiId()).isBlank();
            assertThat(step.segmentId()).startsWith("SEG-" + response.planId() + "-B");
        });
    }

    @Test
    void diningDrinksDiscoveryChainsDiningCardThenDrinksCardBeforeTimeline() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> researchEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204C", "晚上八点后才有空，一个人想一直玩到十二点，帮我看看有什么好吃的和附近好喝的清吧。"),
                researchEvents::add);

        assertThat(response.timeline()).isEmpty();
        assertThat(researchEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.chain.start: DINING_THEN_DRINKS"));
        assertThat(researchEvents).filteredOn(event -> event.actionCard() != null)
                .last()
                .satisfies(event -> {
                    assertThat(event.actionCard().cardKind()).isEqualTo("POI");
                    assertThat(event.actionCard().title()).contains("餐饮");
                    assertThat(event.actionCard().options())
                            .allSatisfy(option -> assertThat(option.planPatch().target().phase()).isEqualTo("DINING"));
                });
        PendingAction initialPending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(initialPending).isNotNull();
        assertThat(initialPending.type()).isEqualTo("SELECT_CANDIDATE");
        assertThat(initialPending.collectedSlots())
                .containsEntry("candidateChain", "DINING_THEN_DRINKS")
                .containsEntry("nextPhase", "DRINKS")
                .containsEntry("orderPreference", "DINING_THEN_DRINKS");

        List<SseEvent> diningSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选第一个",
                null, null, null, null, diningSelectionEvents::add);

        SseEvent diningFinish = diningSelectionEvents.get(diningSelectionEvents.size() - 1);
        assertThat(diningSelectionEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.chain.next: DRINKS"));
        assertThat(diningFinish.timeline()).isEmpty();
        assertThat(diningFinish.actionCard()).isNotNull();
        assertThat(diningFinish.actionCard().title()).contains("清吧");
        assertThat(diningFinish.actionCard().options())
                .allSatisfy(option -> assertThat(option.planPatch().target().phase()).isEqualTo("DRINKS"));
        PendingAction drinksPending = fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction();
        assertThat(drinksPending.selectedPatch()).isNotNull();
        assertThat(drinksPending.selectedLabel()).isNotBlank();
        assertThat(drinksPending.collectedSlots())
                .containsEntry("candidateChain", "DINING_THEN_DRINKS")
                .containsKey("selectedDiningPatch");

        List<SseEvent> drinksSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选第一个",
                null, null, null, null, drinksSelectionEvents::add);

        SseEvent finish = drinksSelectionEvents.get(drinksSelectionEvents.size() - 1);
        assertThat(drinksSelectionEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.chain.complete: build timeline"));
        assertThat(finish.timeline()).isNotEmpty();
        List<String> businessPhases = finish.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .map(PlanStep::phase)
                .toList();
        assertThat(businessPhases).containsExactly("DINING", "DRINKS");
        assertThat(toMinutes(finish.timeline().stream()
                .filter(step -> "DINING".equals(step.phase()))
                .findFirst()
                .orElseThrow()
                .startTime())).isGreaterThanOrEqualTo(20 * 60);
        assertThat(fixture.sessionStateStore().find(response.planId()).orElseThrow().pendingAction()).isNull();
    }

    @Test
    void diningDrinksDiscoverySubmitPatchClickShowsDrinksCard() throws Exception {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> researchEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U204D", "晚上八点后才有空，一个人想一直玩到十二点，帮我看看有什么好吃的和附近好喝的清吧。"),
                researchEvents::add);
        ActionCard.ActionOption diningOption = researchEvents.stream()
                .filter(event -> event.actionCard() != null)
                .reduce((first, second) -> second)
                .orElseThrow()
                .actionCard()
                .options()
                .get(0);

        List<SseEvent> diningSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), diningOption.label(),
                null, "action-card:SUBMIT_PATCH", diningOption.id(),
                new ObjectMapper().writeValueAsString(diningOption.planPatch()), diningSelectionEvents::add);

        SseEvent finish = diningSelectionEvents.get(diningSelectionEvents.size() - 1);
        assertThat(diningSelectionEvents).extracting(SseEvent::content)
                .anySatisfy(content -> assertThat(content).contains("candidate.chain.next: DRINKS"));
        assertThat(finish.timeline()).isEmpty();
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().title()).contains("清吧");
        assertThat(finish.actionCard().options())
                .allSatisfy(option -> assertThat(option.planPatch().target().phase()).isEqualTo("DRINKS"));
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
    void selectedPlanBuildMarkerWithQueueConflictReturnsRepairCard() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U205Q",
                "[BUILD_SELECTED_PLAN] 原始需求：今天 14:00 到 18:00，2 个人，安排吃饭和轻活动，优先附近少绕路。"
                        + "基于推荐的商家（商家ID: P002）生成行程拼图，总共 2 个人。"),
                events::add);

        assertThat(response.timeline()).noneSatisfy(step -> assertThat(step.poiId()).isEqualTo("P002"));
        assertThat(response.conflicts()).isNotEmpty();
        assertThat(response.repairOptions()).isNotEmpty();
        assertThat(events)
                .filteredOn(event -> "FINISH".equals(event.type()))
                .last()
                .satisfies(event -> {
                    assertThat(event.actionCard()).isNotNull();
                    assertThat(event.actionCard().cardKind()).isEqualTo("QUEUE_REPAIR");
                });
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
    void contextAfterPreferenceUsesExplicitSlotCollectionClockRange() {
        Fixture fixture = newFixtureWithResearch();

        List<SseEvent> consultEvents = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U208B", "第一次约会什么项目比较好"), consultEvents::add);

        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:ritual",
                null, null, "action-card:SELECT_PREFERENCE", "pref-ritual", ignored -> {});

        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(),
                "我计划在 14:00 到 22:00 出行，总共 2 个人，我想指定一个商圈或地铁站，先吃饭再玩",
                null, null, null, null, contextEvents::add);

        SseEvent finish = contextEvents.get(contextEvents.size() - 1);
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.actionCard()).isNotNull();
        assertThat(finish.actionCard().options()).isNotEmpty();
        SessionState state = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(state.userConstraints().startTime()).isEqualTo("14:00");
        assertThat(state.userConstraints().endTime()).isEqualTo("22:00");
        assertThat(state.userConstraints().headcount()).isEqualTo(2);
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().startTime()).isEqualTo("14:00");
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().endTime()).isEqualTo("22:00");
        assertThat(fixture.store().find(response.planId()).orElseThrow().intent().headcount()).isEqualTo(2);
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
    void rainFamilyIndoorActivityAndDiningWorkflow() throws Exception {
        Fixture fixture = newFixtureWithResearch();
        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U299", "下雨天带5岁孩子出门玩半天，求推荐纯室内且少步行的放电与温和就餐路线。"), events::add);
        assertThat(response.timeline()).isEmpty();
        assertThat(response.intent().isConsultingMode()).isTrue();
        List<SseEvent> prefEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:indoor_activity_meal",
                null, null, "action-card:SELECT_PREFERENCE", "pref-indoor-meal", prefEvents::add);
        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "下午，附近吧",
                null, null, null, null, contextEvents::add);
        SseEvent contextFinish = contextEvents.get(contextEvents.size() - 1);
        assertThat(contextFinish.actionCard()).isNotNull();
        assertThat(contextFinish.actionCard().options()).allSatisfy(option -> {
            if (option.poiPreview() != null) {
                assertThat(option.poiPreview().tags()).doesNotContain("outdoor");
            }
        });
        boolean hasActivity = contextFinish.actionCard().options().stream()
                .anyMatch(opt -> opt.poiPreview() != null && "ACTIVITY".equals(opt.poiPreview().category()));
        boolean hasDining = contextFinish.actionCard().options().stream()
                .anyMatch(opt -> opt.poiPreview() != null && "RESTAURANT".equals(opt.poiPreview().category()));
        assertThat(hasActivity).isTrue();
        assertThat(hasDining).isTrue();
        PlanPatch selectPatch = contextFinish.actionCard().options().stream()
                .filter(opt -> opt.poiPreview() != null && "P008".equals(opt.poiPreview().poiId()))
                .findFirst()
                .orElseThrow()
                .planPatch();
        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选择 星海儿童探索馆",
                null, "action-card:SUBMIT_PATCH", "contextual-choice",
                new ObjectMapper().writeValueAsString(selectPatch), selectionEvents::add);
        SseEvent selectFinish = selectionEvents.get(selectionEvents.size() - 1);
        assertThat(selectFinish.timeline()).isNotEmpty();
        if (selectFinish.actionCard() != null) {
            assertThat(selectFinish.actionCard().options()).allSatisfy(option -> {
                if (option.poiPreview() != null) {
                    assertThat(option.poiPreview().tags()).doesNotContain("outdoor");
                }
            });
        }
    }

    @Test
    void rainFamilyMovieAndDiningWorkflow() throws Exception {
        Fixture fixture = newFixtureWithResearch();
        List<SseEvent> events = new ArrayList<>();
        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U299", "下雨天带5岁孩子出门玩半天，求推荐纯室内且少步行的放电与温和就餐路线。"), events::add);
        assertThat(response.timeline()).isEmpty();
        assertThat(response.intent().isConsultingMode()).isTrue();
        List<SseEvent> prefEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "PREFERENCE:movie_meal",
                null, null, "action-card:SELECT_PREFERENCE", "pref-movie-meal", prefEvents::add);
        List<SseEvent> contextEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "下午，附近吧",
                null, null, null, null, contextEvents::add);
        SseEvent contextFinish = contextEvents.get(contextEvents.size() - 1);
        assertThat(contextFinish.actionCard()).isNotNull();
        assertThat(contextFinish.actionCard().options()).allSatisfy(option -> {
            if (option.poiPreview() != null) {
                assertThat(option.poiPreview().tags()).doesNotContain("outdoor");
            }
        });
        boolean hasMovieOrCinema = contextFinish.actionCard().options().stream()
                .anyMatch(opt -> opt.poiPreview() != null && 
                        ("CINEMA".equals(opt.poiPreview().category()) || 
                         ("ACTIVITY".equals(opt.poiPreview().category()) && opt.poiPreview().tags().contains("movie"))));
        boolean hasDining = contextFinish.actionCard().options().stream()
                .anyMatch(opt -> opt.poiPreview() != null && "RESTAURANT".equals(opt.poiPreview().category()));
        assertThat(hasMovieOrCinema).isTrue();
        assertThat(hasDining).isTrue();

        ActionCard.ActionOption movieOpt = contextFinish.actionCard().options().stream()
                .filter(opt -> opt.poiPreview() != null && "CINEMA".equals(opt.poiPreview().category()))
                .findFirst()
                .orElseThrow();
        List<SseEvent> movieSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选择电影",
                null, "action-card:SUBMIT_PATCH", "contextual-choice",
                new ObjectMapper().writeValueAsString(movieOpt.planPatch()), movieSelectionEvents::add);
        SseEvent movieSelectionFinish = movieSelectionEvents.get(movieSelectionEvents.size() - 1);

        assertThat(movieSelectionFinish.actionCard()).isNotNull();
        ActionCard.ActionOption diningOpt = movieSelectionFinish.actionCard().options().stream()
                .filter(opt -> opt.poiPreview() != null && "RESTAURANT".equals(opt.poiPreview().category()))
                .findFirst()
                .orElseThrow();
        List<SseEvent> diningSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "选择餐厅",
                null, "action-card:SUBMIT_PATCH", "contextual-choice",
                new ObjectMapper().writeValueAsString(diningOpt.planPatch()), diningSelectionEvents::add);
        SseEvent diningSelectionFinish = diningSelectionEvents.get(diningSelectionEvents.size() - 1);

        List<SseEvent> slotEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "下午两点，玩两小时，两个人，先吃再玩",
                null, null, null, null, slotEvents::add);
        SseEvent slotFinish = slotEvents.get(slotEvents.size() - 1);

        assertThat(slotFinish.timeline()).isNotEmpty();
        boolean hasSelectedMovieInTimeline = slotFinish.timeline().stream()
                .anyMatch(step -> step.poiId().equals(movieOpt.poiPreview().poiId()));
        boolean hasSelectedDiningInTimeline = slotFinish.timeline().stream()
                .anyMatch(step -> step.poiId().equals(diningOpt.poiPreview().poiId()));
        assertThat(hasSelectedMovieInTimeline).isTrue();
        assertThat(hasSelectedDiningInTimeline).isTrue();
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
    void movieResearchSelectionCreatesMoviePlanImmediatelyWithOptionalBuffer() {
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
                .anySatisfy(content -> assertThat(content).contains("pending.workflow.resume: movie_schedule immediate"));
        assertThat(finish.type()).isEqualTo("FINISH");
        assertThat(finish.timeline()).isNotEmpty();
        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.poiName()).isNotBlank();
            assertThat(step.reason()).contains("Selected screening");
            assertThat(step.headcount()).isEqualTo(1);
            assertThat(toMinutes(step.startTime())).isBetween(14 * 60, 18 * 60);
        });
        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.executionStatus()).isEqualTo("BUFFER");
            assertThat(step.phase()).isEqualTo("LEISURE");
            assertThat(step.poiId()).isBlank();
            assertThat(step.segmentId()).startsWith("SEG-" + response.planId() + "-B");
        });

        SessionState afterSelection = fixture.sessionStateStore().find(response.planId()).orElseThrow();
        assertThat(afterSelection.pendingAction()).isNull();
    }

    @Test
    void bufferRecommendationReplacesOnlyBufferAndKeepsSelectedMovie() throws Exception {
        Fixture fixture = newFixtureWithResearch();

        PlanResponse response = fixture.workflow().createPlanStreaming(new PlanRequest(
                "U206", "\u5e2e\u6211\u770b\u770b\u4e0b\u5348\u4e24\u70b9\u6709\u4ec0\u4e48\u7535\u5f71"), event -> {});
        List<SseEvent> selectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u9009\u7b2c\u4e00\u4e2a",
                null, null, null, null, selectionEvents::add);
        SseEvent movieFinish = selectionEvents.get(selectionEvents.size() - 1);
        PlanStep movieStep = movieFinish.timeline().stream()
                .filter(step -> !"BUFFER".equalsIgnoreCase(step.executionStatus()))
                .filter(step -> !step.isTransit())
                .findFirst()
                .orElseThrow();
        PlanStep bufferStep = movieFinish.timeline().stream()
                .filter(step -> "BUFFER".equalsIgnoreCase(step.executionStatus()))
                .findFirst()
                .orElseThrow();

        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(bufferStep.segmentId(), null, "ACTIVITY", "ACTIVITY", null, null),
                new PlanPatch.Requirements(List.of(), List.of(), List.of("NEARBY", "FLEXIBLE_ACTIVITY"),
                        null, null, null, false),
                true);
        List<SseEvent> recommendEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u7ed9\u8fd9\u6bb5\u673a\u52a8\u65f6\u95f4\u52a0\u70b9\u522b\u7684",
                bufferStep.segmentId(), "puzzle-free-slot-recommend", "puzzle-free-slot-recommend",
                new ObjectMapper().writeValueAsString(patch), recommendEvents::add);
        SseEvent recommendFinish = recommendEvents.get(recommendEvents.size() - 1);
        assertThat(recommendFinish.actionCard()).isNotNull();
        assertThat(recommendFinish.actionCard().options()).isNotEmpty();

        List<SseEvent> candidateSelectionEvents = new ArrayList<>();
        fixture.workflow().executeChat(response.planId(), response.userId(), "\u9009\u7b2c\u4e00\u4e2a",
                null, null, null, null, candidateSelectionEvents::add);
        SseEvent finish = candidateSelectionEvents.get(candidateSelectionEvents.size() - 1);

        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.segmentId()).isEqualTo(movieStep.segmentId());
            assertThat(step.poiId()).isEqualTo(movieStep.poiId());
            assertThat(step.poiName()).isEqualTo(movieStep.poiName());
        });
        assertThat(finish.timeline()).anySatisfy(step -> {
            assertThat(step.segmentId()).isEqualTo(bufferStep.segmentId());
            assertThat(step.executionStatus()).isNotEqualTo("BUFFER");
            assertThat(step.poiId()).isNotBlank();
        });
    }

    private Fixture newFixture() {
        return newFixture(false);
    }

    private Fixture newFixtureWithResearch() {
        return newFixture(true);
    }

    private Fixture newFixtureWithResearch(ChatModel planChoiceChatModel) {
        return newFixture(true, planChoiceChatModel);
    }

    private String directPlanPrompt() {
        return "[BUILD_SELECTED_PLAN] \u539f\u59cb\u9700\u6c42\uff1a14:00-18:00\uff0c2 \u4e2a\u4eba\uff0c\u5b89\u6392\u5403\u996d\u52a0\u6d3b\u52a8\uff0c\u522b\u592a\u8fdc\u3002"
                + "\u8bf7\u751f\u6210\u5305\u542b\u6d3b\u52a8\u548c\u9910\u5385\u7684\u884c\u7a0b\u62fc\u56fe\u3002";
    }

    private Fixture newFixture(boolean includeResearch) {
        return newFixture(includeResearch, null);
    }

    private Fixture newFixture(boolean includeResearch, ChatModel planChoiceChatModel) {
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
        ReflectionTestUtils.setField(consultationWorkflow, "movieListingProvider", new SandboxMovieListingProvider());
        ObjectProvider<ChatModel> chatModelProvider = null;
        if (planChoiceChatModel != null) {
            chatModelProvider = mock(ObjectProvider.class);
            when(chatModelProvider.getIfAvailable()).thenReturn(planChoiceChatModel);
        }
        WorkflowActionService actions = new WorkflowActionService(
                fastPlanEngine, store, intentExtractor, patchExtractor, deltaExtractor, editorEngine,
                replacementSearchEngine, contextAssembler, router, sessionStateStore, objectMapper,
                new AgentRuntimeProperties(), null, null, null, new InitialRequestRouter(),
                researchRenderWorkflow, consultationWorkflow, null, null, chatModelProvider);
        PlanPalGraphRuntime graphRuntime = new PlanPalGraphRuntime(new com.weekendplanner.engine.graph.PlanGraphConfig(), new com.weekendplanner.engine.graph.PlanGraphNodes(actions), objectMapper);
        AgentWorkflowEngine workflow = new AgentWorkflowEngine(graphRuntime, actions);
        return new Fixture(store, sessionStateStore, workflow);
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
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
