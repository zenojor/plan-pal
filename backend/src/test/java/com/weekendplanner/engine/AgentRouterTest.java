package com.weekendplanner.engine;

import com.weekendplanner.engine.context.ContextPack;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.runtime.AgentCommand;
import com.weekendplanner.engine.runtime.RouteMode;
import com.weekendplanner.engine.routing.AgentRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.ConstraintSet;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AgentRouterTest {

    private final AgentRouter router = new AgentRouter((org.springframework.ai.chat.model.ChatModel) null, new ObjectMapper());

    @Test
    void pendingCandidateSelectionRoutesSecondReplyToSelectCandidate() {
        AgentCommand command = router.route(contextWithPending("第二个吧"));

        assertThat(command.intent()).isEqualTo("SELECT_CANDIDATE");
        assertThat(command.command()).isEqualTo("APPLY_CANDIDATE_TO_PLAN");
        assertThat(command.selectedIndex()).isEqualTo(2);
        assertThat(command.candidateSetId()).isEqualTo("candidates-1");
        assertThat(command.routeMode()).isEqualTo(RouteMode.FAST_WORKFLOW);
    }

    @Test
    void nearReplacementReplyRoutesToFastReplacementWorkflow() {
        AgentCommand command = router.route(contextWithPending("换个近一点的"));

        assertThat(command.intent()).isEqualTo("REPLACE_SEGMENT");
        assertThat(command.command()).isEqualTo("REPLACE_SEGMENT_WITH_CANDIDATES");
        assertThat(command.slots()).containsEntry("distancePreference", "nearer");
        assertThat(command.routeMode()).isEqualTo(RouteMode.FAST_WORKFLOW);
    }

    @Test
    void candidatePreferenceReplyRefinesCurrentReplacementSearch() {
        AgentCommand command = router.route(contextWithPending("有没有火锅"));

        assertThat(command.intent()).isEqualTo("REFINE_CANDIDATES");
        assertThat(command.command()).isEqualTo("REPLACE_SEGMENT_WITH_CANDIDATES");
        assertThat(command.targetSegmentId()).isEqualTo("seg-1");
        assertThat(command.candidateSetId()).isEqualTo("candidates-1");
        assertThat(command.slots()).containsEntry("strictTags", true)
                .containsEntry("phase", "DINING")
                .containsEntry("category", "RESTAURANT");
        Object includeTags = command.slots().get("includeTags");
        assertThat(includeTags).isInstanceOf(List.class);
        assertThat(((List<?>) includeTags).stream().map(String::valueOf).toList()).contains("hotpot");
    }

    @Test
    void candidatePreferenceReplyCanSwitchToDrinksSearch() {
        AgentCommand command = router.route(contextWithPending("\u60f3\u559d\u9152"));

        assertThat(command.intent()).isEqualTo("REFINE_CANDIDATES");
        assertThat(command.command()).isEqualTo("REPLACE_SEGMENT_WITH_CANDIDATES");
        assertThat(command.targetSegmentId()).isEqualTo("seg-1");
        assertThat(command.candidateSetId()).isEqualTo("candidates-1");
        assertThat(command.slots()).containsEntry("strictTags", true)
                .containsEntry("phase", "DRINKS")
                .containsEntry("category", "RESTAURANT");
        Object includeTags = command.slots().get("includeTags");
        assertThat(includeTags).isInstanceOf(List.class);
        assertThat(((List<?>) includeTags).stream().map(String::valueOf).toList()).contains("bar");
    }

    @Test
    void extendingToTenPmRoutesToEditTime() {
        AgentCommand command = router.route(contextWithoutPending("延长到晚上十点"));

        assertThat(command.intent()).isEqualTo("EDIT_TIME");
        assertThat(command.command()).isEqualTo("EXTEND_PLAN_END_TIME");
        assertThat(command.slots()).containsEntry("newEndTime", "22:00");
        assertThat(command.routeMode()).isEqualTo(RouteMode.FAST_WORKFLOW);
    }

    @Test
    void vagueOptimizationRoutesToFastPatchWorkflow() {
        AgentCommand command = router.route(contextWithoutPending("这个安排感觉怪怪的，帮我优化一下"));

        assertThat(command.routeMode()).isEqualTo(RouteMode.FAST_WORKFLOW);
        assertThat(command.command()).isEqualTo("APPLY_FEEDBACK_PATCH");
    }

    @Test
    void planChoicePendingSelectionDoesNotUseCandidateApplyCommand() {
        AgentCommand command = router.route(contextWithPlanChoicePending("第二个吧"));

        assertThat(command.intent()).isEqualTo("SELECT_PLAN_CHOICE");
        assertThat(command.command()).isEqualTo("BUILD_SELECTED_PLAN_CHOICE");
        assertThat(command.selectedIndex()).isEqualTo(2);
        assertThat(command.routeMode()).isEqualTo(RouteMode.FAST_WORKFLOW);
    }

    private ContextPack contextWithPending(String input) {
        PendingAction pending = new PendingAction("SELECT_CANDIDATE", "candidates-1", "seg-1",
                List.of("选择第几个", "换一批", "取消"));
        return new ContextPack("U001", "plan-1", input, null, null, pending, List.of(), List.of(), ConstraintSet.fromIntent(null), List.of(), 1);
    }

    private ContextPack contextWithPlanChoicePending(String input) {
        PendingAction pending = new PendingAction("PLAN_CHOICE", null, null,
                List.of("choose plan option", "ask question"), "PLAN_CHOICE", null, null,
                List.of("choice"), java.util.Map.of("choice.2.poiIds", List.of("P003", "P004")), true);
        return new ContextPack("U001", "plan-1", input, null, null, pending, List.of(), List.of(), ConstraintSet.fromIntent(null), List.of(), 1);
    }

    private ContextPack contextWithoutPending(String input) {
        return new ContextPack("U001", "plan-1", input, null, null, null, List.of(), List.of(), ConstraintSet.fromIntent(null), List.of(), 1);
    }
}
