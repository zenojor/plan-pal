package com.weekendplanner.engine;


import com.weekendplanner.engine.intent.IntentExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class IntentExtractorTest {

    private final IntentExtractor extractor = new IntentExtractor((ChatModel) null, new ObjectMapper());

    @Test
    void ruleFallbackExtractsPreferenceFields() {
        PlanIntent intent = extractor.extract("14:00到18:00，2个人，带5岁孩子，低预算，步行，别太赶，避开户外，必须室内，下雨也能玩");

        assertThat(intent.headcount()).isEqualTo(2);
        assertThat(intent.pace()).isEqualTo("RELAXED");
        assertThat(intent.budgetLevel()).isEqualTo("LOW");
        assertThat(intent.hasChildren()).isTrue();
        assertThat(intent.childAge()).isEqualTo(5);
        assertThat(intent.preferredTransportMode()).isEqualTo("WALK");
        assertThat(intent.avoid()).contains("户外");
        assertThat(intent.mustHave()).contains("室内");
        assertThat(intent.weatherSensitive()).isTrue();
    }

    @Test
    void adjustmentMergeOnlyOverridesMentionedPreferenceFields() {
        PlanIntent original = extractor.extract("14:00到18:00，2个人，低预算，步行，别太赶，吃饭加活动");
        PlanIntent adjusted = extractor.mergeForAdjustment(original, "预算高一点，改成开车");

        assertThat(adjusted.budgetLevel()).isEqualTo("HIGH");
        assertThat(adjusted.preferredTransportMode()).isEqualTo("DRIVE");
        assertThat(adjusted.pace()).isEqualTo(original.pace());
        assertThat(adjusted.headcount()).isEqualTo(original.headcount());
        assertThat(adjusted.startTime()).isEqualTo(original.startTime());
    }

    @Test
    void numericTwoPersonPromptIsSocialRatherThanFamily() {
        PlanIntent intent = extractor.extract("今天 14:00 到 22:00，2 个人，安排吃饭和轻活动，优先附近少绕路，可以更紧凑，多安排一个点。");

        assertThat(intent.headcount()).isEqualTo(2);
        assertThat(intent.sceneType()).isEqualTo("SOCIAL");
        assertThat(intent.hasChildren()).isFalse();
        assertThat(intent.requestedSegments()).contains("DINING", "LEISURE");
    }

    @Test
    void adjustmentMergePreservesAndAppendsRequestedSegments() {
        PlanIntent original = extractor.extract("14:00到18:00，1个人，吃饭加活动");
        assertThat(original.requestedSegments()).containsExactlyInAnyOrder("DINING", "LEISURE");

        PlanIntent adjusted = extractor.mergeForAdjustment(original, "晚上想去喝点酒");
        assertThat(adjusted.requestedSegments()).containsExactlyInAnyOrder("DINING", "LEISURE", "DRINKS");
    }

    @Test
    void adjustmentMergeOnlyOverridesEndTimeWhenExtending() {
        PlanIntent original = extractor.extract("14:00到18:00，1个人，吃饭加活动");
        PlanIntent adjusted = extractor.mergeForAdjustment(original, "帮我顺延行程时间至晚上 21:00，并在后面加上喝酒");

        assertThat(adjusted.startTime()).isEqualTo("14:00");
        assertThat(adjusted.endTime()).isEqualTo("21:00");
        assertThat(adjusted.totalMinutes()).isEqualTo(420); // 7 hours
    }

    @Test
    void familySizeHeadcountAndSceneAreParsedCorrectly() {
        PlanIntent intent1 = extractor.extract("一家三口周末想轻松安排一下，最好能吃饭、散步、给孩子放电");
        assertThat(intent1.headcount()).isEqualTo(3);
        assertThat(intent1.sceneType()).isEqualTo("FAMILY");
        assertThat(intent1.hasChildren()).isTrue();

        PlanIntent intent2 = extractor.extract("带娃娃去公园逛逛，下午两点出发");
        assertThat(intent2.headcount()).isEqualTo(2);
        assertThat(intent2.sceneType()).isEqualTo("FAMILY");
        assertThat(intent2.hasChildren()).isTrue();
    }

    @Test
    void childAndFriendPromptDoesNotCollapseToTwoPeople() {
        PlanIntent intent = extractor.extract("周六下午带 5 岁孩子和朋友在本地玩 4 小时，别太远，要好吃好走。");

        assertThat(intent.headcount()).isEqualTo(3);
        assertThat(intent.sceneType()).isEqualTo("FAMILY");
        assertThat(intent.hasChildren()).isTrue();
    }

    @Test
    void specificPromptExtractsOnlyDiningAndDrinksWithoutLeisure() {
        PlanIntent intent = extractor.extract("晚上八点后才有空，一个人想一直玩到十二点，帮我看看有什么好吃的和附近好喝的清吧。");

        assertThat(intent.requestedSegments()).containsExactlyInAnyOrder("DINING", "DRINKS");
        assertThat(intent.requestedSegments()).doesNotContain("LEISURE");
        assertThat(intent.headcount()).isEqualTo(1);
        assertThat(intent.sceneType()).isEqualTo("SOLO");
    }
}

