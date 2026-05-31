package com.weekendplanner.engine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InitialRequestRouterTest {

    private final InitialRequestRouter router = new InitialRequestRouter();

    @Test
    void dateIdeaQuestionStartsConsultChat() {
        InitialRouteCommand command = router.route("第一次约会什么项目比较好");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CONSULT_CHAT);
        assertThat(command.researchType()).isEqualTo("IDEA");
    }

    @Test
    void movieSearchAtTwoStaysInResearchMode() {
        InitialRouteCommand command = router.route("帮我看看下午两点有什么电影");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.RESEARCH_AND_RENDER);
        assertThat(command.researchType()).isEqualTo("MOVIE");
        assertThat(command.evidence().afterTime()).isEqualTo("14:00");
    }

    @Test
    void explicitTimedPlanCreatesPlan() {
        InitialRouteCommand command = router.route("14:00-18:00，3个人，安排吃饭散步");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CREATE_PLAN);
    }

    @Test
    void nearbyFoodSearchStaysInResearchMode() {
        InitialRouteCommand command = router.route("附近有什么吃的");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.RESEARCH_AND_RENDER);
        assertThat(command.researchType()).isEqualTo("DINING");
    }

    @Test
    void vaguePlanWithoutSlotsAsksClarification() {
        InitialRouteCommand command = router.route("帮我安排一个完整行程");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.ASK_CLARIFICATION);
    }
}
