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
                objectMapper);
        ReflectionTestUtils.setField(engine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(engine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(engine, "queueThresholdMinutes", 30);
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

    private int minutesBetween(String start, String end) {
        return toMinutes(end) - toMinutes(start);
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
