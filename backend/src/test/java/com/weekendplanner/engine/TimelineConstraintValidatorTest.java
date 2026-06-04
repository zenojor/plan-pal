package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.planning.TimelineConstraintValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TimelineConstraintValidatorTest {

    private final TimelineConstraintValidator validator = new TimelineConstraintValidator();

    @Test
    void acceptsTimelineThatHonorsExplicitStartEndOrderAndLockedPoi() {
        TimelineConstraintValidator.Result result = validator.validate(List.of(
                step(120, "10:00", "12:00", "ACTIVITY", "A001"),
                transit("12:00", "12:20"),
                step(80, "12:20", "13:40", "DINING", "R001")
        ), null, lockedDiningPending());

        assertThat(result.valid()).isTrue();
        assertThat(result.conflicts()).isEmpty();
    }

    @Test
    void rejectsTimelineThatFallsBackToDefaultStartTime() {
        TimelineConstraintValidator.Result result = validator.validate(List.of(
                step(120, "14:00", "16:00", "ACTIVITY", "A001"),
                step(80, "16:00", "17:20", "DINING", "R001")
        ), null, lockedDiningPending());

        assertThat(result.valid()).isFalse();
        assertThat(result.conflicts()).anyMatch(conflict -> "StartTimeMismatch".equals(conflict.conflictType()));
    }

    @Test
    void rejectsMissingLockedPoiAndWrongActivityDiningOrder() {
        TimelineConstraintValidator.Result result = validator.validate(List.of(
                step(80, "10:00", "11:20", "DINING", "R999"),
                step(120, "11:20", "13:20", "ACTIVITY", "A001")
        ), null, lockedDiningPending());

        assertThat(result.valid()).isFalse();
        assertThat(result.conflicts()).anyMatch(conflict -> "LockedCandidateMissing".equals(conflict.conflictType()));
        assertThat(result.conflicts()).anyMatch(conflict -> "OrderPreferenceViolation".equals(conflict.conflictType()));
    }

    @Test
    void rejectsTimelineThatExceedsExplicitMaxEndTime() {
        TimelineConstraintValidator.Result result = validator.validate(List.of(
                step(180, "10:00", "13:00", "ACTIVITY", "A001"),
                transit("13:00", "13:30"),
                step(80, "13:30", "14:50", "DINING", "R001")
        ), null, lockedDiningPending());

        assertThat(result.valid()).isFalse();
        assertThat(result.conflicts()).anyMatch(conflict -> "EndTimeExceeded".equals(conflict.conflictType()));
    }

    private PendingAction lockedDiningPending() {
        PlanPatch patch = new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(null, null, "DINING", "DINING"),
                new PlanPatch.Requirements(List.of(), List.of(),
                        List.of("SELECTED_POI:R001"), null, null, null, false),
                false);
        return new PendingAction("PLAN_SLOT_FILLING", null, null, List.of("time", "duration", "headcount"),
                "DINING_LOCKED_PLAN", patch, "海边餐厅",
                List.of("startTime", "duration", "headcount", "orderPreference"),
                Map.of("startTime", "10:00",
                        "maxEndTime", "14:00",
                        "orderPreference", "ACTIVITY_THEN_DINING",
                        "explicit:startTime", true,
                        "explicit:orderPreference", true),
                true);
    }

    private PlanStep step(int duration, String start, String end, String phase, String poiId) {
        return new PlanStep(duration, start, end, phase, phase + " action", poiId, poiId,
                "OPTIONAL", "", new double[]{120.0, 30.0}, "adult", "", "MEDIUM",
                3, "", "PENDING", "");
    }

    private PlanStep transit(String start, String end) {
        return new PlanStep(20, start, end, "TRANSIT", "Transit", "", "",
                "", "", new double[]{120.0, 30.0}, "", "", "",
                0, "", "", "", true, "DRIVE", 2.0, "A", "B");
    }
}
