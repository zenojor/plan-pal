package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.engine.candidate.CandidateSet;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.interaction.PendingSlotFiller;
import com.weekendplanner.engine.interaction.PendingSlotPatch;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PendingSlotFillerTest {

    private final PendingSlotFiller filler = new PendingSlotFiller();

    @Test
    void fillsMovieTimeRangeAndLocationWithoutTreatingItAsGenericSearch() {
        PendingSlotPatch patch = filler.extract(moviePending(), "下午吧就附近", null);

        assertThat(patch.shouldContinueWorkflow()).isTrue();
        assertThat(patch.question()).isFalse();
        assertThat(patch.slots())
                .containsEntry("timeRange", "AFTERNOON")
                .containsEntry("locationScope", "NEARBY")
                .containsEntry("explicit:timeRange", true)
                .containsEntry("explicit:locationScope", true);
        assertThat(patch.slots()).doesNotContainKey("startTime");
    }

    @Test
    void keepsMoviePendingWhenUserAsksReadOnlyMovieQuestion() {
        PendingSlotPatch patch = filler.extract(moviePending(), "这个电影讲什么", null);

        assertThat(patch.question()).isTrue();
        assertThat(patch.shouldContinueWorkflow()).isFalse();
        assertThat(patch.slots()).isEmpty();
    }

    @Test
    void recoversMovieWorkflowFromRecentMovieCandidate() {
        SessionState state = new SessionState("s1", "p1", "u1", List.of(),
                List.of(new CandidateSet("movies-1", "MOVIE", null, List.of(), Instant.now())),
                null, null, List.of(), List.of(), Instant.now());
        PendingAction genericPending = new PendingAction("ASK_CONTEXT", null, null, List.of(),
                "CONTEXTUAL_RESEARCH", null, null, List.of(), Map.of(), true);

        PendingSlotPatch patch = filler.extract(genericPending, "我说的是电影呀", state);

        assertThat(patch.correction()).isTrue();
        assertThat(patch.shouldContinueWorkflow()).isTrue();
        assertThat(patch.reason()).isEqualTo("pending.movie.correction");
    }

    @Test
    void parsesDiningLockedPlanSlotsWithExplicitProvenance() {
        PendingSlotPatch patch = filler.extract(diningPending(),
                "上午十点开始，大概三四个小时吧，玩完再去吃饭", null);

        assertThat(patch.shouldContinueWorkflow()).isTrue();
        assertThat(patch.slots())
                .containsEntry("timeRange", "MORNING")
                .containsEntry("startTime", "10:00")
                .containsEntry("minDurationMinutes", 180)
                .containsEntry("maxDurationMinutes", 240)
                .containsEntry("durationMinutes", 240)
                .containsEntry("maxEndTime", "14:00")
                .containsEntry("endTime", "14:00")
                .containsEntry("orderPreference", "ACTIVITY_THEN_DINING")
                .containsEntry("explicit:startTime", true)
                .containsEntry("explicit:orderPreference", true);
    }

    @Test
    void parsesFriendHeadcountWithoutAddingCurrentUserUnlessMentioned() {
        assertThat(filler.extract(diningPending(), "三个朋友", null).slots())
                .containsEntry("headcount", 3);
        assertThat(filler.extract(diningPending(), "我和三个朋友", null).slots())
                .containsEntry("headcount", 4);
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

    private PendingAction diningPending() {
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(null, null, "DINING", "DINING"),
                new PlanPatch.Requirements(List.of(), List.of(),
                        List.of("SELECTED_POI:R001"), null, null, null, false),
                false);
        return new PendingAction("PLAN_SLOT_FILLING", null, null, List.of("time", "duration", "headcount"),
                "DINING_LOCKED_PLAN", patch, "海边餐厅", List.of("startTime", "duration", "headcount"),
                Map.of(), true);
    }
}
