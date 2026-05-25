package com.weekendplanner.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.tool.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FastPlanEngineTest {

    private FastPlanEngine newEngine() {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolRegistry registry = new ToolRegistry(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));

        FastPlanEngine engine = new FastPlanEngine(
                registry,
                new IntentExtractor((ChatModel) null, objectMapper),
                new PlanExecutionStore(),
                poiDatabase,
                objectMapper);
        ReflectionTestUtils.setField(engine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(engine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(engine, "queueThresholdMinutes", 60);
        ReflectionTestUtils.setField(engine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(engine, "maxChecksPerCategory", 3);
        return engine;
    }

    @Test
    void familyPlanReturnsEditableDraftWithoutExecutingOrders() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U001",
                "周末带老婆和5岁孩子出去玩半天，下午2点出发，找亲子活动和轻食餐厅"));

        assertThat(response.status()).isIn("SUCCESS", "DEGRADED");
        assertThat(response.timeline()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.timeline()).filteredOn(step -> !step.isTransit()).allSatisfy(step -> {
            assertThat(step.phase()).isIn("ACTIVITY", "DINING", "LEISURE");
            assertThat(step.action()).isNotBlank();
            assertThat(step.poiName()).isNotBlank();
        });
        assertThat(response.trace()).anySatisfy(trace -> assertThat(trace.type()).isEqualTo("ACTION"));
        assertThat(response.executionStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(response.orderGroupId()).isBlank();
        assertThat(response.orderIntents()).isNotEmpty();
    }

    @Test
    void socialPlanReturnsActivityAndDiningOptions() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U002",
                "今天下午想和朋友出去玩，4个人，吃饭加轻活动，别折腾太久"));

        assertThat(response.timeline()).extracting("phase")
                .contains("DINING")
                .anyMatch(phase -> "ACTIVITY".equals(phase) || "LEISURE".equals(phase));
        assertThat(response.summary()).isNotBlank();
        assertThat(response.notificationText()).isNotBlank();
    }

    @Test
    void streamingEmitsPlanStepBeforeFinish() {
        FastPlanEngine engine = newEngine();
        List<SseEvent> events = new ArrayList<>();

        engine.executePlanStreaming(new PlanRequest(
                "U003",
                "一家三口周末想轻松安排一下，最好能吃饭、散步、给孩子放电"),
                events::add);

        assertThat(events).anySatisfy(event -> assertThat(event.type()).isEqualTo("PLAN_STEP"));
        assertThat(events.get(events.size() - 1).type()).isEqualTo("FINISH");
        assertThat(events.stream().filter(event -> "PLAN_STEP".equals(event.type())).count())
                .isGreaterThanOrEqualTo(2);
    }

    @Test
    void nightSoloBarIntentUsesEightToMidnightWindow() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U004",
                "我现在晚上八点后才有时间，一个人想一直玩到十二点，帮我想一份方案，看看有没有什么吃的，附近有没有什么好喝的bar"));

        assertThat(response.intent().headcount()).isEqualTo(1);
        assertThat(response.intent().startTime()).isEqualTo("20:00");
        assertThat(response.intent().endTime()).isEqualTo("24:00");
        assertThat(response.timeline().get(0).startTime()).isEqualTo("20:00");
        assertThat(response.timeline().get(response.timeline().size() - 1).endTime()).isEqualTo("24:00");
        assertThat(response.timeline()).extracting("phase").contains("DINING", "DRINKS");
    }

    @Test
    void noSpicyConstraintIsReflectedInDiningPlan() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U005",
                "今天晚上我和老婆吃饭散步，她不能吃辣，帮我安排一下"));

        assertThat(response.intent().dietaryConstraints()).contains("NO_SPICY");
        assertThat(response.timeline()).anySatisfy(step -> {
            if ("DINING".equals(step.phase())) {
                assertThat(step.constraints()).contains("NO_SPICY");
                assertThat(step.reason()).contains("避开");
            }
        });
    }

    @Test
    void businessDurationsAndTransitAreScheduledExplicitly() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U007",
                "今天下午想和朋友出去玩，4个人，吃饭加轻活动，别折腾太久"));

        assertThat(response.timeline()).anySatisfy(step -> {
            assertThat(step.phase()).isEqualTo("TRANSIT");
            assertThat(step.isTransit()).isTrue();
            assertThat(step.transportMode()).isNotBlank();
            assertThat(step.distanceKm()).isGreaterThan(0);
        });
        assertThat(response.timeline()).filteredOn(step -> !step.isTransit()).allSatisfy(step ->
                assertThat(minutesBetween(step.startTime(), step.endTime())).isEqualTo(step.durationMinutes()));
    }

    @Test
    void expandedFoodAndDrinkMocksAreReachable() {
        FastPlanEngine engine = newEngine();

        PlanResponse spicy = engine.executePlan(new PlanRequest("U008", "我想吃辣，最好是烧烤或者火锅"));
        PlanResponse quietBar = engine.executePlan(new PlanRequest("U009", "我想喝酒，要安静一点的bar"));
        PlanResponse club = engine.executePlan(new PlanRequest("U010", "今晚朋友想去club"));
        PlanResponse smoothie = engine.executePlan(new PlanRequest("U011", "附近有没有什么冰沙店"));

        assertThat(spicy.timeline()).anySatisfy(step ->
                assertThat(step.reason()).containsAnyOf("spicy", "bbq", "hotpot", "sichuan", "hunan", "烧烤", "火锅"));
        assertThat(quietBar.timeline()).anySatisfy(step ->
                assertThat(step.poiName()).contains("清吧"));
        assertThat(club.timeline()).anySatisfy(step ->
                assertThat(step.poiName()).contains("Club"));
        assertThat(smoothie.timeline()).anySatisfy(step ->
                assertThat(step.poiName()).contains("冰沙"));
    }

    @Test
    void preferenceFieldsInfluenceChildIndoorWalkingPlan() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U012",
                "14:00到18:00，2个人，带5岁孩子，低预算，步行，别太赶，避开户外，必须室内，下雨也能玩"));

        assertThat(response.intent().pace()).isEqualTo("RELAXED");
        assertThat(response.intent().budgetLevel()).isEqualTo("LOW");
        assertThat(response.intent().hasChildren()).isTrue();
        assertThat(response.intent().childAge()).isEqualTo(5);
        assertThat(response.intent().preferredTransportMode()).isEqualTo("WALK");
        assertThat(response.intent().weatherSensitive()).isTrue();
        assertThat(response.timeline()).filteredOn(step -> !step.isTransit()).anySatisfy(step ->
                assertThat((step.reason() + step.poiName()).toLowerCase()).contains("indoor"));
        assertThat(response.timeline()).noneSatisfy(step ->
                assertThat((step.reason() + step.poiName()).toLowerCase()).contains("adult_only"));
    }

    @Test
    void compactPaceAllowsAtLeastAsManyBusinessStepsAsRelaxedPace() {
        FastPlanEngine engine = newEngine();

        PlanResponse relaxed = engine.executePlan(new PlanRequest(
                "U013",
                "14:00到20:00，2个人，别太赶，吃饭加活动"));
        PlanResponse compact = engine.executePlan(new PlanRequest(
                "U014",
                "14:00到20:00，2个人，紧凑一点，多安排，吃饭加活动"));

        long relaxedBusinessSteps = relaxed.timeline().stream().filter(step -> !step.isTransit()).count();
        long compactBusinessSteps = compact.timeline().stream().filter(step -> !step.isTransit()).count();
        assertThat(compactBusinessSteps).isGreaterThanOrEqualTo(relaxedBusinessSteps);
    }

    @Test
    void avoidTermsFilterMatchingCandidates() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U015",
                "14:00到18:00，4个人，朋友想喝bar，但是避开club"));

        assertThat(response.intent().avoid()).contains("club");
        assertThat(response.timeline()).noneSatisfy(step ->
                assertThat(step.poiName().toLowerCase()).contains("club"));
    }

    @Test
    void explicitPoiIdsArePreservedAndScheduledCorrectly() {
        FastPlanEngine engine = newEngine();

        // Option 1: P016 (特色小吃街, RESTAURANT) and P022 (雾岛安静清吧, RESTAURANT with bar tags)
        PlanResponse response1 = engine.executePlan(new PlanRequest(
                "U016",
                "帮我把推荐的商家（商户ID: P016、P022）规划到下午 14:00 到 18:00 的行程拼图中，总共 1 个人"));

        assertThat(response1.timeline())
                .filteredOn(step -> step.poiId() != null && !step.poiId().isBlank())
                .extracting(step -> step.poiId())
                .isNotEmpty()
                .allMatch(poiId -> poiId.equals("P016") || poiId.equals("P022"));

        // Option 2: P028 (小橘子果汁咖啡, RESTAURANT) and P019 (微醺小酒馆, RESTAURANT with bar tags)
        PlanResponse response2 = engine.executePlan(new PlanRequest(
                "U017",
                "帮我把推荐的商家（商户ID: P028、P019）规划到下午 14:00 到 18:00 的行程拼图中，总共 1 个人"));

        assertThat(response2.timeline())
                .filteredOn(step -> step.poiId() != null && !step.poiId().isBlank())
                .extracting(step -> step.poiId())
                .isNotEmpty()
                .allMatch(poiId -> poiId.equals("P028") || poiId.equals("P019"));
    }

    private int minutesBetween(String start, String end) {
        return toMinutes(end) - toMinutes(start);
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
