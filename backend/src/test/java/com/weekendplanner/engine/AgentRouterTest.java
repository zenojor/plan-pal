package com.weekendplanner.engine;



import com.weekendplanner.engine.context.AgentContext;
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

    private AgentContext contextWithPending(String input) {
        PendingAction pending = new PendingAction("SELECT_CANDIDATE", "candidates-1", "seg-1",
                List.of("选择第几个", "换一批", "取消"));
        SessionState state = new SessionState("session-1", "plan-1", "U001", List.of(), List.of(),
                pending, ConstraintSet.fromIntent(null), List.of(), List.of(), Instant.now());
        return new AgentContext(input, null, state, null, null, null);
    }

    private AgentContext contextWithoutPending(String input) {
        SessionState state = new SessionState("session-1", "plan-1", "U001", List.of(), List.of(),
                null, ConstraintSet.fromIntent(null), List.of(), List.of(), Instant.now());
        return new AgentContext(input, null, state, null, null, null);
    }
}
