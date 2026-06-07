package com.weekendplanner.engine;


import com.weekendplanner.engine.intent.IntentValidator;
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
                List.of(), List.of(), "", "NEARBY", "出去走走，散散步"
        );

        PlanIntent validated = validator.validate(invalidIntent, "出去走走，散散步");
        assertThat(validated.headcount()).isEqualTo(1); // Default headcount repaired to 1 if <= 0
        assertThat(validated.sceneType()).isEqualTo("SOLO");
    }

    @Test
    void testValidateRepairsHeadcountForExplicitKeywords() {
        PlanIntent dateIntent = new PlanIntent(
                0, List.of(), "14:00", "18:00", 240, null,
                List.of(), List.of(), "", "NEARBY", "和女朋友约会去哪"
        );
        PlanIntent dateValidated = validator.validate(dateIntent, "和女朋友约会去哪");
        assertThat(dateValidated.headcount()).isEqualTo(2);
        assertThat(dateValidated.sceneType()).isEqualTo("DATE");

        PlanIntent familyIntent = new PlanIntent(
                1, List.of(), "14:00", "18:00", 240, null,
                List.of(), List.of(), "", "NEARBY", "一家三口周末出行"
        );
        PlanIntent familyValidated = validator.validate(familyIntent, "一家三口周末出行");
        assertThat(familyValidated.headcount()).isEqualTo(3);
        assertThat(familyValidated.sceneType()).isEqualTo("FAMILY");
    }

    @Test
    void testValidateEnforcesMinimumHeadcountForChildAndFriend() {
        PlanIntent undercountedIntent = new PlanIntent(
                2, List.of("孩子", "朋友"), "14:00", "18:00", 240, "FAMILY",
                List.of(), List.of(), "", "NEARBY", "周六下午带 5 岁孩子和朋友在本地玩 4 小时"
        );

        PlanIntent validated = validator.validate(undercountedIntent, "周六下午带 5 岁孩子和朋友在本地玩 4 小时");
        assertThat(validated.headcount()).isEqualTo(3);
        assertThat(validated.sceneType()).isEqualTo("FAMILY");
        assertThat(validated.hasChildren()).isTrue();
    }

    @Test
    void testValidateDemotesFamilyWithoutFamilyCue() {
        PlanIntent llmFamilyIntent = new PlanIntent(
                2, List.of(), "14:00", "22:00", 480, "FAMILY",
                List.of("DINING", "LEISURE"), List.of(), "", "NEARBY",
                "今天 14:00 到 22:00，2 个人，安排吃饭和轻活动"
        );

        PlanIntent validated = validator.validate(llmFamilyIntent, llmFamilyIntent.originalPrompt());
        assertThat(validated.sceneType()).isEqualTo("SOCIAL");
        assertThat(validated.hasChildren()).isFalse();
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
