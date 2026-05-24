package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentValidatorTest {

    private final IntentValidator validator = new IntentValidator();

    @Test
    void testValidateRepairsInvalidSceneType() {
        PlanIntent invalidIntent = new PlanIntent(
                1, List.of(), "14:00", "18:00", 240, "INVALID_SCENE",
                List.of("ACTIVITY"), List.of(), "", "NEARBY", "一个人去逛逛"
        );

        PlanIntent validated = validator.validate(invalidIntent, "一个人去逛逛");
        assertThat(validated.sceneType()).isEqualTo("SOLO");
    }

    @Test
    void testValidateRepairsHeadcountAndSceneType() {
        PlanIntent invalidIntent = new PlanIntent(
                0, List.of(), "14:00", "18:00", 240, null,
                List.of(), List.of(), "", "NEARBY", "和女朋友约会去哪"
        );

        PlanIntent validated = validator.validate(invalidIntent, "和女朋友约会去哪");
        assertThat(validated.headcount()).isEqualTo(1); // Default headcount repaired to 1 if <= 0
        assertThat(validated.sceneType()).isEqualTo("DATE");
    }

    @Test
    void testValidateRepairsTimes() {
        PlanIntent invalidIntent = new PlanIntent(
                2, List.of(), "下午两点", "18:00", 0, "DATE",
                List.of(), List.of(), "", "NEARBY", "约会"
        );

        PlanIntent validated = validator.validate(invalidIntent, "约会");
        assertThat(validated.startTime()).isEqualTo("14:00");
        assertThat(validated.endTime()).isEqualTo("18:00");
        assertThat(validated.totalMinutes()).isEqualTo(240);
    }

    @Test
    void testValidateFiltersInvalidSegments() {
        PlanIntent invalidIntent = new PlanIntent(
                2, List.of(), "14:00", "18:00", 240, "DATE",
                List.of("SHOPPING", "DINING", "INVALID"), List.of(), "", "NEARBY", "约会"
        );

        PlanIntent validated = validator.validate(invalidIntent, "约会");
        assertThat(validated.requestedSegments()).containsExactly("DINING");
    }

    @Test
    void testIsMissingCriticalInfo() {
        PlanIntent missingTimeIntent = new PlanIntent(
                2, List.of("朋友"), "14:00", "18:00", 240, "SOCIAL",
                List.of(), List.of(), "", "NEARBY", "跟朋友聚会，2个人"
        );
        assertThat(validator.isMissingCriticalInfo(missingTimeIntent)).isTrue(); // missing time because it defaults to 14:00 and prompt doesn't mention time

        PlanIntent completeIntent = new PlanIntent(
                2, List.of("朋友"), "15:00", "19:00", 240, "SOCIAL",
                List.of(), List.of(), "", "NEARBY", "跟朋友三点到七点聚会，2个人"
        );
        assertThat(validator.isMissingCriticalInfo(completeIntent)).isFalse();
    }
}
