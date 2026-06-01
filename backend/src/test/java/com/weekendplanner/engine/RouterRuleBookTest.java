package com.weekendplanner.engine;


import com.weekendplanner.engine.routing.RouterRuleBook;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouterRuleBookTest {

    private final RouterRuleBook ruleBook = new RouterRuleBook();

    @Test
    void routesCoreContextRepliesFromSharedRuleBook() {
        assertThat(ruleBook.selectedIndex("第二个吧")).contains(2);
        assertThat(ruleBook.isReplacementRequest("换个近一点")).isTrue();
        assertThat(ruleBook.replacementSlots("太远了")).containsEntry("distancePreference", "nearer");
        assertThat(ruleBook.parseEndTime("延长到晚上十点")).contains("22:00");
        assertThat(ruleBook.isCancelRequest("取消")).isTrue();
        assertThat(ruleBook.isReasoningRequest("这个安排怪怪的，帮我优化")).isTrue();
    }
}
