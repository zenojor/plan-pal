package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.DraftDigest;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.interaction.InteractionCommand;
import com.weekendplanner.engine.interaction.InteractionDecision;
import com.weekendplanner.engine.interaction.InteractionRouter;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InteractionRouterPendingWorkflowTest {

    private final InteractionRouter router = new InteractionRouter(null, new ObjectMapper());

    @Test
    void pendingSlotAnswersContinueWorkflowBeforeLlmRouting() {
        InteractionDecision decision = router.route(context("下午吧就附近", moviePending()), "chat", null);

        assertThat(decision.command()).isEqualTo(InteractionCommand.CONTINUE_WORKFLOW);
        assertThat(decision.reason()).startsWith("pending.slot.fill");
    }

    @Test
    void pendingReadOnlyQuestionsRouteToQaWithoutClearingWorkflow() {
        InteractionDecision decision = router.route(context("这个电影讲什么", moviePending()), "chat", null);

        assertThat(decision.command()).isEqualTo(InteractionCommand.CONVERSATIONAL_QA);
        assertThat(decision.reason()).isEqualTo("pending workflow read-only question");
    }

    @Test
    void structuredPatchStillWinsOverPendingNaturalLanguage() {
        InteractionDecision decision = router.route(context("下午吧就附近", moviePending()),
                "chat", "{\"editType\":\"REPLACE\"}");

        assertThat(decision.command()).isEqualTo(InteractionCommand.MODIFY_PLAN);
        assertThat(decision.reason()).isEqualTo("structured patch payload");
    }

    @Test
    void planChoiceActionCardBuildContinuesWorkflow() {
        InteractionDecision decision = router.route(context("BUILD_PLAN:choice-2", planChoicePending()),
                "action-card:BUILD_PLAN plan-choice-p1-2", null);

        assertThat(decision.command()).isEqualTo(InteractionCommand.CONTINUE_WORKFLOW);
        assertThat(decision.reason()).isEqualTo("continue plan choice workflow");
    }

    @Test
    void planChoiceNaturalSelectionContinuesWorkflow() {
        InteractionDecision decision = router.route(context("第二个吧", planChoicePending()), "chat", null);

        assertThat(decision.command()).isEqualTo(InteractionCommand.CONTINUE_WORKFLOW);
        assertThat(decision.reason()).isEqualTo("continue plan choice workflow");
    }

    @Test
    void planChoiceQuestionRoutesToQa() {
        InteractionDecision decision = router.route(context("哪个更适合下雨？", planChoicePending()), "chat", null);

        assertThat(decision.command()).isEqualTo(InteractionCommand.CONVERSATIONAL_QA);
        assertThat(decision.reason()).isEqualTo("plan choice read-only question");
    }

    private ContextPack context(String userInput, PendingAction pending) {
        PlanIntent intent = new PlanIntent(2, List.of(), "14:00", "18:00", 240,
                "SOCIAL", List.of("MOVIE"), List.of(), null, "NEARBY", "推荐电影");
        PlanExecutionStore.DraftPlan draft = new PlanExecutionStore.DraftPlan(
                "p1", "u1", intent, List.of(), List.of(), "");
        return new ContextPack("u1", "p1", userInput, DraftDigest.fromDraft(draft), null, pending, List.of(), List.of(), ConstraintSet.fromIntent(intent), List.of(), 1);
    }

    private PendingAction planChoicePending() {
        return new PendingAction("PLAN_CHOICE", null, null, List.of("choose plan option", "ask question"),
                "PLAN_CHOICE", null, null, List.of("choice"),
                Map.of("choice.1.poiIds", List.of("P001", "P002"), "choice.2.poiIds", List.of("P003", "P004")), true);
    }

    private PendingAction moviePending() {
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(null, null, "MOVIE", "MOVIE"),
                new PlanPatch.Requirements(List.of(), List.of(),
                        List.of("MOVIE_TITLE:星际穿越", "MOVIE_ID:M001"), null, null, null, false),
                false);
        return new PendingAction("MOVIE_SCHEDULING", null, null, List.of("time", "location", "headcount"),
                "MOVIE", patch, "星际穿越", List.of("timeWindow", "locationScope", "headcount"),
                Map.of(), true);
    }
}
