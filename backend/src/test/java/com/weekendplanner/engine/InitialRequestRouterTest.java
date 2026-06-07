package com.weekendplanner.engine;

import com.weekendplanner.engine.routing.InitialRequestRouter;
import com.weekendplanner.engine.routing.InitialRouteCommand;
import com.weekendplanner.engine.routing.InitialRouteMode;
import com.weekendplanner.engine.routing.InitialTurnRouter;
import com.weekendplanner.engine.understanding.DomainIntent;
import com.weekendplanner.engine.understanding.RouteTarget;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InitialRequestRouterTest {

    private final InitialRequestRouter router = new InitialRequestRouter();

    @Test
    void modelIdentityQuestionStaysInQaMode() {
        InitialRouteCommand command = router.route("你是什么模型");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CONVERSATIONAL_QA);
        assertThat(command.researchType()).isEqualTo("QA");
    }

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
    void homepageMoviePromptStaysInMovieResearchMode() {
        InitialRouteCommand command = router.route("最近有没有什么好看的电影");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.RESEARCH_AND_RENDER);
        assertThat(command.researchType()).isEqualTo("MOVIE");
    }

    @Test
    void explicitTimedPlanCreatesPlan() {
        InitialRouteCommand command = router.route("14:00-18:00，2个人，安排吃饭散步");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CREATE_PLAN);
    }

    @Test
    void diningAndDrinksDiscoveryStartsWithDiningResearch() {
        InitialRouteCommand command = router.route("晚上八点后才有空，一个人想一直玩到十二点，帮我看看有什么好吃的和附近好喝的清吧。");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.RESEARCH_AND_RENDER);
        assertThat(command.researchType()).isEqualTo("DINING");
    }

    @Test
    void explicitDiningAndDrinksRoutePlanCreatesPlan() {
        InitialRouteCommand command = router.route("晚上八点后帮我安排吃饭加清吧路线");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CREATE_PLAN);
    }

    @Test
    void completeFamilyFriendPromptCreatesPlanWithoutConsulting() {
        InitialRouteCommand command = router.route("周六下午带 5 岁孩子和朋友在本地玩 4 小时，别太远，要好吃好走。");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CREATE_PLAN);
    }

    @Test
    void coarseWeekendFamilyPromptAsksForTimeBeforePlanning() {
        InitialRouteCommand command = router.route("一家三口周末想轻松安排一下，最好能吃饭、散步、给孩子放电。");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.ASK_CLARIFICATION);
        assertThat(command.evidence().timeSignal()).isFalse();
        assertThat(command.evidence().headcountSignal()).isTrue();
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

    @Test
    void initialTurnRouterDelegatesNaturalLanguageToUnderstandingService() {
        TurnUnderstandingService understandingService = mock(TurnUnderstandingService.class);
        when(understandingService.understandInitial("讲个笑话")).thenReturn(
                new TurnUnderstanding(TurnIntent.SMALLTALK, DomainIntent.NON_TRIP, RouteTarget.QA,
                        Map.of(), List.of(), true, null, 0.91, "test.smalltalk"));

        InitialTurnRouter initialTurnRouter = new InitialTurnRouter(understandingService);
        InitialRouteCommand command = initialTurnRouter.route("讲个笑话");

        assertThat(command.mode()).isEqualTo(InitialRouteMode.CONVERSATIONAL_QA);
        assertThat(command.understanding().turnIntent()).isEqualTo(TurnIntent.SMALLTALK);
        verify(understandingService).understandInitial("讲个笑话");
    }

    @Test
    void initialRequestRouterDoesNotOwnNaturalLanguageKeywordTables() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/com/weekendplanner/engine/routing/InitialRequestRouter.java"));

        assertThat(source)
                .doesNotContain("PLAN_KEYWORDS")
                .doesNotContain("EXPLORATION_KEYWORDS")
                .doesNotContain("REASONING_KEYWORDS")
                .doesNotContain("isProductOrIdentityQuestion");
    }
}
