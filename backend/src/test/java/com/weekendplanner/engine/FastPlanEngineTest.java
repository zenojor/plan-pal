package com.weekendplanner.engine;


import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.planning.PlanNarrativeBuilder;
import com.weekendplanner.engine.planning.PlanningToolOrchestrator;
import com.weekendplanner.engine.planning.SearchTaskCompiler;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.workflow.FastPlanEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.CheckResponse;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanRequest;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.SseEvent;
import com.weekendplanner.dto.WeatherSnapshot;
import com.weekendplanner.engine.candidate.AvailabilitySelection;
import com.weekendplanner.engine.candidate.CandidateScorer;
import com.weekendplanner.engine.candidate.CandidateProfile;
import com.weekendplanner.mock.MockOrderSystem;
import com.weekendplanner.mock.MockPoiDatabase;
import com.weekendplanner.provider.AvailabilityProvider;
import com.weekendplanner.provider.WeatherProvider;
import com.weekendplanner.provider.SandboxWeatherProvider;
import com.weekendplanner.tool.*;
import com.weekendplanner.engine.tooling.ToolCatalog;
import com.weekendplanner.engine.tooling.ToolRunner;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class FastPlanEngineTest {



    private FastPlanEngine newEngine() {
        return newEngine(heavyRain(false));
    }

    private FastPlanEngine newEngine(WeatherProvider weatherProvider) {
        return newEngine(weatherProvider, (AvailabilityProvider) null);
    }

    private FastPlanEngine newEngine(WeatherProvider weatherProvider, AvailabilityProvider availabilityProvider) {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);

        FastPlanEngine engine = new FastPlanEngine(
                runner,
                new IntentExtractor((ChatModel) null, objectMapper),
                new PlanExecutionStore(),
                poiDatabase,
                objectMapper,
                new TimelineAssembler(),
                new SearchTaskCompiler(),
                availabilityProvider == null
                        ? new PlanningToolOrchestrator(poiDatabase)
                        : new PlanningToolOrchestrator(poiDatabase, availabilityProvider, new CandidateScorer(), 6),
                weatherProvider,
                new PlanNarrativeBuilder());
        ReflectionTestUtils.setField(engine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(engine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(engine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(engine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(engine, "maxChecksPerCategory", 3);
        return engine;
    }

    private FastPlanEngine newEngine(WeatherProvider weatherProvider, PlanningToolOrchestrator orchestrator) {
        ObjectMapper objectMapper = new ObjectMapper();
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        MockOrderSystem orderSystem = new MockOrderSystem();
        ToolCatalog catalog = new ToolCatalog(
                new LocationExplorationTool(poiDatabase, objectMapper),
                new RestaurantReservationTool(poiDatabase, objectMapper),
                new RestaurantBookingTool(orderSystem, objectMapper),
                new TicketingTool(orderSystem, objectMapper),
                new ActionExecutionTool(orderSystem, objectMapper));
        ToolRunner runner = new ToolRunner(catalog, objectMapper);

        FastPlanEngine engine = new FastPlanEngine(
                runner,
                new IntentExtractor((ChatModel) null, objectMapper),
                new PlanExecutionStore(),
                poiDatabase,
                objectMapper,
                new TimelineAssembler(),
                new SearchTaskCompiler(),
                orchestrator,
                weatherProvider,
                new PlanNarrativeBuilder());
        ReflectionTestUtils.setField(engine, "defaultRadiusKm", 3);
        ReflectionTestUtils.setField(engine, "maxRadiusKm", 5);
        ReflectionTestUtils.setField(engine, "queueThresholdMinutes", 30);
        ReflectionTestUtils.setField(engine, "deadlineMs", 25_000L);
        ReflectionTestUtils.setField(engine, "maxChecksPerCategory", 3);
        return engine;
    }

    private WeatherProvider heavyRain(boolean highRisk) {
        return (city, date, startTime, endTime) -> new WeatherSnapshot(
                city == null ? "上海" : city,
                date == null ? LocalDate.of(2026, 5, 25) : date,
                highRisk ? "HEAVY_RAIN" : "CLEAR",
                highRisk ? 20 : 26,
                highRisk ? 90 : 10,
                highRisk ? 5 : 2,
                highRisk ? "HIGH" : "LOW",
                highRisk ? "大雨，户外活动风险较高，建议改成室内项目。" : "天气晴好，户外和室内活动都可安排。",
                highRisk ? List.of("indoor", "sheltered", "mall", "museum", "cafe") : List.of("outdoor", "citywalk", "indoor"),
                highRisk ? List.of("outdoor", "citywalk", "sports") : List.of());
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
    void longDiningQueueReturnsRepairChoicesWithoutFreeBuffer() {
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        AvailabilityProvider longDiningQueue = (poiId, targetTime, headcount) -> poiDatabase.findById(poiId)
                .filter(poi -> "RESTAURANT".equalsIgnoreCase(poi.category()))
                .map(poi -> new CheckResponse(poiId, "QUEUED", 49, true, "test", "trace", "",
                        "QUEUE_TOO_LONG", poiId))
                .orElseGet(() -> new CheckResponse(poiId, "AVAILABLE", 0, false, "test", "trace", "",
                        "available", poiId));
        FastPlanEngine engine = newEngine(heavyRain(false), longDiningQueue);
        List<SseEvent> events = new ArrayList<>();

        PlanResponse response = engine.executePlanStreaming(new PlanRequest(
                "U020",
                "14:00到18:00，3个人，安排亲子活动和吃饭"),
                events::add);

        assertThat(response.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.conflictType()).isEqualTo("QUEUE_CONFLICT");
            assertThat(conflict.reason()).contains("49");
        });
        assertThat(response.repairOptions()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(response.timeline()).allSatisfy(step -> {
            assertThat(step.poiName()).doesNotContain("就近自由安排");
            assertThat(step.action()).doesNotContain("自由缓冲");
        });
        assertThat(events).filteredOn(event -> "FINISH".equals(event.type()))
                .last()
                .satisfies(event -> {
                    assertThat(event.actionCard()).isNotNull();
                    assertThat(event.actionCard().cardKind()).isEqualTo("QUEUE_REPAIR");
                    assertThat(event.actionCard().description()).contains("49");
                    assertThat(event.actionCard().options()).hasSizeGreaterThanOrEqualTo(2);
                });
    }

    @Test
    void firstDiningCandidateQueueIssueReturnsRepairChoicesEvenWhenFallbackIsAvailable() {
        MockPoiDatabase poiDatabase = new MockPoiDatabase();
        PlanningToolOrchestrator orchestrator = new PlanningToolOrchestrator(poiDatabase) {
            @Override
            public AvailabilitySelection selectAvailable(String phase, List<CandidateProfile> candidates,
                                                          PlanIntent intent, String targetTime, Set<String> usedPoiIds) {
                if ("DINING".equalsIgnoreCase(phase)) {
                    CheckResponse queued = new CheckResponse("P002", "QUEUED", 49, true, "test", "trace", "",
                            "QUEUE_TOO_LONG", "P002");
                    CheckResponse available = new CheckResponse("P005", "AVAILABLE", 0, false, "test", "trace", "",
                            "available", "P005");
                    CandidateProfile original = new CandidateProfile(poiDatabase.findById("P002").orElseThrow(),
                            phase, 100.0, List.of(), queued, List.of("test"), "QUEUED/49min");
                    CandidateProfile fallback = new CandidateProfile(poiDatabase.findById("P005").orElseThrow(),
                            phase, 90.0, List.of(), available, List.of("test"), null);
                    return new AvailabilitySelection(fallback.poi(), available, false, null,
                            List.of(original, fallback));
                }
                return super.selectAvailable(phase, candidates, intent, targetTime, usedPoiIds);
            }
        };
        FastPlanEngine engine = newEngine(heavyRain(false), orchestrator);
        List<SseEvent> events = new ArrayList<>();

        PlanResponse response = engine.executePlanStreaming(new PlanRequest(
                "U023",
                "14:00\u523018:00\uff0c3\u4e2a\u4eba\uff0c\u5b89\u6392\u5403\u996d\u548c\u8f7b\u6d3b\u52a8\uff0c\u9644\u8fd1"),
                events::add);

        assertThat(response.conflicts()).anySatisfy(conflict ->
                assertThat(conflict.conflictType()).isEqualTo("QUEUE_CONFLICT"));
        assertThat(events).filteredOn(event -> "FINISH".equals(event.type()))
                .last()
                .satisfies(event -> {
                    assertThat(event.actionCard()).isNotNull();
                    assertThat(event.actionCard().cardKind()).isEqualTo("QUEUE_REPAIR");
                });
    }

    @Test
    void explicitLooseRequestMayUseMobilityBuffer() {
        FastPlanEngine engine = newEngine();

        PlanResponse response = engine.executePlan(new PlanRequest(
                "U021",
                "14:00到18:00，2个人，安排吃饭，轻松一点，留点空"));

        assertThat(response.timeline()).anySatisfy(step -> assertThat(step.executionStatus()).isEqualTo("BUFFER"));
        assertThat(response.summary()).contains("可执行节点");
        assertThat(response.summary()).doesNotContain("就近自由安排");
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

        long relaxedBusinessSteps = relaxed.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> !"BUFFER".equalsIgnoreCase(step.executionStatus()))
                .count();
        long compactBusinessSteps = compact.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> !"BUFFER".equalsIgnoreCase(step.executionStatus()))
                .count();
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

    @Test
    void highRiskWeatherInfluencesPlanEvenWhenPromptDoesNotMentionWeather() {
        FastPlanEngine engine = newEngine(heavyRain(true));
        MockPoiDatabase poiDatabase = new MockPoiDatabase();

        PlanResponse response = engine.executePlanStreaming(new PlanRequest("U018", "plan"), null,
                new PlanIntent(4, List.of("friends"), "14:00", "18:00", 240,
                        "SOCIAL", List.of("ACTIVITY"), List.of(), "NONE", "NEARBY",
                        "friends activity", "NORMAL", "MEDIUM", false, null,
                        "PUBLIC_TRANSIT", List.of(), List.of(), false, false));

        assertThat(response.weather()).isNotNull();
        assertThat(response.weather().condition()).isEqualTo("HEAVY_RAIN");
        assertThat(response.intent().weatherSensitive()).isTrue();
        assertThat(response.timeline()).filteredOn(step -> "ACTIVITY".equals(step.phase()) || "LEISURE".equals(step.phase()))
                .filteredOn(step -> step.poiId() != null && !step.poiId().isBlank())
                .allSatisfy(step -> assertThat(poiDatabase.findById(step.poiId()).orElseThrow().tags())
                        .contains("indoor"));
    }

    @Test
    void highRiskWeatherCreatesConflictWhenExplicitOutdoorPoiIsKept() {
        FastPlanEngine engine = newEngine(heavyRain(true));

        PlanResponse response = engine.executePlanStreaming(new PlanRequest("U019", "plan P004"), null,
                new PlanIntent(2, List.of("friends"), "14:00", "16:30", 150,
                        "SOCIAL", List.of("ACTIVITY"), List.of(), "NONE", "NEARBY",
                        "please keep P004", "NORMAL", "MEDIUM", false, null,
                        "PUBLIC_TRANSIT", List.of(), List.of(), false, false));

        assertThat(response.timeline()).anySatisfy(step -> assertThat(step.poiId()).isEqualTo("P004"));
        assertThat(response.conflicts()).anySatisfy(conflict -> {
            assertThat(conflict.conflictType()).isEqualTo("WEATHER_CONFLICT");
            assertThat(conflict.severity()).isEqualTo("HIGH");
        });
        assertThat(response.repairOptions()).anySatisfy(option ->
                assertThat(option.optionId()).startsWith("replace-weather-"));
    }

    private int minutesBetween(String start, String end) {
        return toMinutes(end) - toMinutes(start);
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }
}
