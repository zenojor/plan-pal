package com.weekendplanner.engine.workflow;



import com.weekendplanner.engine.intent.IntentExtractor;
import com.weekendplanner.engine.planning.PlanNarrativeBuilder;
import com.weekendplanner.engine.planning.PlanningToolOrchestrator;
import com.weekendplanner.engine.planning.SearchTask;
import com.weekendplanner.engine.planning.SearchTaskCompiler;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.tooling.ToolResult;
import com.weekendplanner.engine.candidate.AvailabilitySelection;
import com.weekendplanner.engine.candidate.CandidatePool;
import com.weekendplanner.engine.candidate.CandidateProfile;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.exception.AgentPlanningException;
import com.weekendplanner.mock.GeoUtils;
import com.weekendplanner.provider.PoiProvider;
import com.weekendplanner.provider.AvailabilityProvider;
import com.weekendplanner.provider.SandboxWeatherProvider;
import com.weekendplanner.provider.WeatherProvider;
import com.weekendplanner.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 有界混合规划引擎
 *
 * LLM 不再控制多轮工具循环；后端以固定上限完成检索、打分、可用性检查、
 * 重规划、预约和通知，保证比赛演示能在 30 秒内稳定产出。
 */
@Component
public class FastPlanEngine {

    private static final Logger log = LoggerFactory.getLogger(FastPlanEngine.class);

    private final ToolRegistry toolRegistry;
    private final IntentExtractor intentExtractor;
    private final PlanExecutionStore executionStore;
    private final PoiProvider poiDatabase;
    private final ObjectMapper objectMapper;
    private final TimelineAssembler timelineAssembler;
    private final SearchTaskCompiler searchTaskCompiler;
    private final PlanningToolOrchestrator planningToolOrchestrator;
    private final WeatherProvider weatherProvider;
    private final PlanNarrativeBuilder narrativeBuilder;

    @Value("${agent.default-radius-km:3}")
    private int defaultRadiusKm;

    @Value("${agent.max-radius-km:5}")
    private int maxRadiusKm;

    @Value("${agent.queue-threshold-minutes:30}")
    private int queueThresholdMinutes;

    @Value("${agent.fast.deadline-ms:25000}")
    private long deadlineMs;

    @Value("${agent.fast.max-checks-per-category:3}")
    private int maxChecksPerCategory;

    @Value("${planner.default.city:上海}")
    private String defaultCity;

    @Autowired
    public FastPlanEngine(ToolRegistry toolRegistry,
                          IntentExtractor intentExtractor,
                          PlanExecutionStore executionStore,
                          PoiProvider poiDatabase,
                          ObjectMapper objectMapper,
                          TimelineAssembler timelineAssembler,
                          SearchTaskCompiler searchTaskCompiler,
                          PlanningToolOrchestrator planningToolOrchestrator,
                          WeatherProvider weatherProvider,
                          PlanNarrativeBuilder narrativeBuilder) {
        this.toolRegistry = toolRegistry;
        this.intentExtractor = intentExtractor;
        this.executionStore = executionStore;
        this.poiDatabase = poiDatabase;
        this.objectMapper = objectMapper;
        this.timelineAssembler = timelineAssembler;
        this.searchTaskCompiler = searchTaskCompiler;
        this.planningToolOrchestrator = planningToolOrchestrator;
        this.weatherProvider = weatherProvider;
        this.narrativeBuilder = narrativeBuilder;
    }

    public FastPlanEngine(ToolRegistry toolRegistry,
                          IntentExtractor intentExtractor,
                          PlanExecutionStore executionStore,
                          PoiProvider poiDatabase,
                          ObjectMapper objectMapper) {
        this(toolRegistry, intentExtractor, executionStore, poiDatabase, objectMapper, new SandboxWeatherProvider());
    }

    public FastPlanEngine(ToolRegistry toolRegistry,
                          IntentExtractor intentExtractor,
                          PlanExecutionStore executionStore,
                          PoiProvider poiDatabase,
                          ObjectMapper objectMapper,
                          WeatherProvider weatherProvider) {
        this(toolRegistry, intentExtractor, executionStore, poiDatabase, objectMapper, new TimelineAssembler(),
                new SearchTaskCompiler(), new PlanningToolOrchestrator(poiDatabase), weatherProvider,
                new PlanNarrativeBuilder());
    }

    public PlanResponse executePlan(PlanRequest request) {
        return executePlanInternal(request, null, null);
    }

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return executePlanInternal(request, emitter, null);
    }

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent intent) {
        return executePlanInternal(request, emitter, intent);
    }

    private PlanResponse executePlanInternal(PlanRequest request, Consumer<SseEvent> emitter, PlanIntent passedIntent) {
        long deadlineAt = System.currentTimeMillis() + deadlineMs;
        String planId = request.planId() != null ? request.planId() : UUID.randomUUID().toString().substring(0, 8);
        PlanIntent intent;
        if (passedIntent != null) {
            intent = passedIntent;
        } else if (request.planId() != null) {
            Optional<PlanExecutionStore.DraftPlan> originalOpt = executionStore.find(request.planId());
            if (originalOpt.isPresent()) {
                PlanIntent originalIntent = originalOpt.get().intent();
                PlanIntent newIntent = intentExtractor.extract(request.prompt());
                intent = mergeIntents(originalIntent, newIntent);
            } else {
                intent = intentExtractor.extract(request.prompt());
            }
        } else {
            intent = intentExtractor.extract(request.prompt());
        }
        TraceRecorder trace = new TraceRecorder(emitter, intent, narrativeBuilder);

        List<PlanStep> timeline = new ArrayList<>();
        List<OrderIntent> orderIntents = new ArrayList<>();
        List<String> degradationNotes = new ArrayList<>();
        List<Conflict> planningConflicts = new ArrayList<>();
        List<RepairOption> planningRepairOptions = new ArrayList<>();
        ActionCard planningDecisionCard = null;

        log.info("[FastPlan] 开始 planId={}, scene={}, start={}, end={}, headcount={}",
                planId, intent.sceneType(), intent.startTime(), intent.endTime(), intent.headcount());
        trace.start("开始有界规划，目标是在 30 秒内生成可执行方案");
        trace.thought("已解析需求：" + describeIntent(intent));

        WeatherSnapshot weather = weatherProvider.snapshot(defaultCity, LocalDate.now(), intent.startTime(), intent.endTime());
        trace.weather(weather);

        PlanNarrativeBuilder.Narrative narrative = narrativeBuilder.buildInitial(intent, weather);
        trace.intent(narrative.intentBrief(), intent);
        trace.narrative(narrative.planningBrief());

        PlanIntent planningIntent = enrichIntentForWeather(intent, weather);

        List<SegmentSlot> slots = allocateSlots(planningIntent);
        List<SearchTask> searchTasks = searchTaskCompiler.compile(planningIntent, slots.stream().map(SegmentSlot::phase).toList(), weather);
        trace.action("PlanningToolOrchestrator.collectCandidates",
                "tasks=" + searchTasks.size() + ", phases=" + slots.stream().map(SegmentSlot::phase).distinct().toList());
        CandidatePool candidatePool = planningToolOrchestrator.collectCandidates(planId, planningIntent, searchTasks, weather);
        degradationNotes.addAll(candidatePool.degradationNotes());
        trace.observation("candidatePool phases=" + candidatePool.phaseCandidates().keySet()
                + ", candidates=" + candidatePool.phaseCandidates().entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue().size()).toList()
                + ", stats=" + candidatePool.taskStats().stream()
                .map(stat -> stat.taskId() + "/" + stat.phase() + "/" + stat.resultCount() + "/" + stat.elapsedMs() + "ms").toList());
        int cursorMinutes = toMinutes(planningIntent.startTime());
        int planEndMinutes = toMinutes(planningIntent.endTime());
        PlanStep previousBusinessStep = null;
        Set<String> usedPoiIds = new HashSet<>(); // 跨 slot 去重：同一个 POI 不会被选多次

        for (SegmentSlot slot : slots) {
            if (isDeadlineClose(deadlineAt)) {
                degradationNotes.add("规划时间接近上限，后续节点已按可确认草案处理。");
                break;
            }

            String targetTime = formatMinutes(cursorMinutes);
            List<CandidateProfile> candidates = candidatePool.candidatesFor(slot.phase());
            trace.action("PlanningToolOrchestrator.checkAvailability",
                    "phase=" + slot.phase() + ", topN=" + Math.min(maxChecksPerCategory, candidates.size())
                            + ", targetTime=" + targetTime);
            AvailabilitySelection availabilitySelection = planningToolOrchestrator.selectAvailable(
                    slot.phase(), candidates, planningIntent, targetTime, usedPoiIds);
            trace.observation("availability phase=" + slot.phase() + ", checked="
                    + availabilitySelection.checkedCandidates().stream()
                    .map(candidate -> candidate.poi().poiId() + ":" + candidate.poi().name()
                            + ":" + (candidate.availability() == null ? "NONE" : candidate.availability().status())
                            + ":" + (candidate.rejectionReason() == null ? "accepted" : candidate.rejectionReason()))
                    .toList());
            Selection selection = new Selection(availabilitySelection.poi(), availabilitySelection.availability(),
                    availabilitySelection.degraded(), availabilitySelection.degradationNote());
            if (selection.poi() != null) {
                usedPoiIds.add(selection.poi().poiId());
            }

            if (selection.poi() == null) {
                QueueDecision queueDecision = buildQueueDecision(planId, slot, targetTime,
                        availabilitySelection.checkedCandidates(), planningIntent);
                if (queueDecision != null) {
                    planningConflicts.add(queueDecision.conflict());
                    planningRepairOptions.addAll(queueDecision.repairOptions());
                    planningDecisionCard = queueDecision.actionCard();
                    degradationNotes.add(queueDecision.conflict().reason());
                    trace.thought(queueDecision.conflict().reason());
                    continue;
                }
                degradationNotes.add("没有找到实时可用的 " + slot.phase() + " 候选，已收拢行程并跳过该补位。");
                trace.thought("没有找到实时可用的 " + slot.phase() + " 候选，跳过该 slot，不生成占位拼图");
                continue;
            }

            if (selection.degraded()) degradationNotes.add(selection.degradationNote());
            if (previousBusinessStep != null) {
                PlanStep transitStep = buildTransitStep(previousBusinessStep, selection.poi(), cursorMinutes, planningIntent);
                if (transitStep != null) {
                    timeline.add(transitStep);
                    cursorMinutes += transitStep.durationMinutes();
                    trace.planStep("已补上交通拼图：" + transitStep.action(), timeline);
                }
            }

            SegmentSlot timedSlot = new SegmentSlot(slot.phase(), formatMinutes(cursorMinutes),
                    formatMinutes(cursorMinutes + slot.durationMinutes()), slot.durationMinutes());
            OrderIntent orderIntent = buildOrderIntent(planId, orderIntents.size() + 1, selection.poi(), timedSlot, planningIntent);
            if (orderIntent != null) orderIntents.add(orderIntent);

            PlanStep step = buildPlanStep(selection, timedSlot, planningIntent, orderIntent);
            timeline.add(step);
            previousBusinessStep = step;
            cursorMinutes += step.durationMinutes();
            trace.planStep("已确认草案拼图：" + step.poiName(), timeline);
        }

        if (timeline.isEmpty()) {
            throw new AgentPlanningException("当前条件下没有找到实时可用的商户或活动，请放宽时间、距离或偏好后重试。");
        }

        if (previousBusinessStep != null && cursorMinutes < planEndMinutes) {
            int bufferMinutes = planEndMinutes - cursorMinutes;
            if (shouldExtendLastExecutableStep(planningIntent, bufferMinutes)) {
                PlanStep extendedStep = extendStepTo(previousBusinessStep, planEndMinutes);
                replaceLastBusinessStep(timeline, previousBusinessStep, extendedStep);
                previousBusinessStep = extendedStep;
                cursorMinutes = planEndMinutes;
                trace.planStep("Extended final executable stop: " + extendedStep.poiName(), timeline);
            } else if (shouldAddBuffer(planningIntent, bufferMinutes)) {
                PlanStep bufferStep = buildBufferStep(planningIntent, cursorMinutes, planEndMinutes);
                timeline.add(bufferStep);
                trace.planStep("已补上预留机动时间：" + bufferStep.action(), timeline);
            }
        }

        boolean degraded = !degradationNotes.isEmpty();
        timeline = new ArrayList<>(timelineAssembler.ensureSegmentIds(planId, timeline));
        String degradationNote = degraded ? String.join("；", degradationNotes) : null;
        String status = degraded ? "DEGRADED" : "SUCCESS";
        List<Conflict> conflicts = new ArrayList<>(planningConflicts);
        conflicts.addAll(detectWeatherConflicts(timeline, weather));
        List<RepairOption> repairOptions = new ArrayList<>(planningRepairOptions);
        repairOptions.addAll(weatherRepairOptions(conflicts, timeline));
        String summary = buildSummary(planningIntent, timeline, degraded);
        String notificationText = buildNotification(planningIntent, timeline, degraded);

        PlanExecutionStore.DraftPlan saved = new PlanExecutionStore.DraftPlan(planId, request.userId(), planningIntent,
                List.copyOf(timeline), List.copyOf(orderIntents), notificationText);
        PlanResponse response = new PlanResponse(planId, request.userId(), status, summary,
                List.copyOf(timeline), trace.finish(summary), "", notificationText, degradationNote,
                planningIntent, List.copyOf(orderIntents), "PENDING_CONFIRMATION", saved.version(),
                saved.status(), conflicts, repairOptions, weather);

        executionStore.save(saved);

        trace.emitFinish(response, planningDecisionCard);
        log.info("[FastPlan] 完成 planId={}, status={}, steps={}, orderIntents={}",
                planId, status, timeline.size(), orderIntents.size());
        return response;
    }

    private PlanIntent enrichIntentForWeather(PlanIntent intent, WeatherSnapshot weather) {
        if (!hasWeatherRisk(weather) || intent == null) return intent;
        return new PlanIntent(
                intent.headcount(),
                intent.participants(),
                intent.startTime(),
                intent.endTime(),
                intent.totalMinutes(),
                intent.sceneType(),
                intent.requestedSegments(),
                intent.dietaryConstraints(),
                intent.drinkPreference(),
                intent.locationScope(),
                intent.originalPrompt(),
                intent.pace(),
                intent.budgetLevel(),
                intent.hasChildren(),
                intent.childAge(),
                intent.preferredTransportMode(),
                intent.avoid(),
                mergeTags(intent.mustHave(), weather.preferredTags()),
                true,
                intent.isConsultingMode());
    }

    private boolean hasWeatherRisk(WeatherSnapshot weather) {
        return weather != null
                && ("MEDIUM".equalsIgnoreCase(weather.outdoorRiskLevel())
                || "HIGH".equalsIgnoreCase(weather.outdoorRiskLevel()));
    }

    private boolean hasHighWeatherRisk(WeatherSnapshot weather) {
        return weather != null && "HIGH".equalsIgnoreCase(weather.outdoorRiskLevel());
    }

    private List<Conflict> detectWeatherConflicts(List<PlanStep> timeline, WeatherSnapshot weather) {
        if (!hasHighWeatherRisk(weather) || timeline == null || timeline.isEmpty()) return List.of();
        List<String> affected = timeline.stream()
                .filter(step -> step != null && !step.isTransit())
                .filter(this::isOutdoorStep)
                .map(PlanStep::segmentId)
                .filter(segmentId -> segmentId != null && !segmentId.isBlank())
                .toList();
        if (affected.isEmpty()) return List.of();
        return List.of(new Conflict(
                "WEATHER_CONFLICT",
                "HIGH",
                affected,
                weather.summary(),
                List.of()));
    }

    private List<RepairOption> weatherRepairOptions(List<Conflict> conflicts, List<PlanStep> timeline) {
        if (conflicts == null || conflicts.stream().noneMatch(c -> "WEATHER_CONFLICT".equalsIgnoreCase(c.conflictType()))) {
            return List.of();
        }
        PlanStep target = timeline.stream()
                .filter(step -> step != null && !step.isTransit() && isOutdoorStep(step))
                .findFirst()
                .orElse(null);
        if (target == null) return List.of();
        PlanPatch patch = new PlanPatch(
                "MODIFY_PLAN",
                "REPLACE",
                new PlanPatch.Target(target.segmentId(), null, target.phase(), null, null, null),
                new PlanPatch.Requirements(List.of(), List.of("outdoor", "citywalk"), List.of("indoor", "sheltered", "museum", "mall"),
                        null, null, null, false),
                true);
        return List.of(
                new RepairOption(
                        "replace-weather-" + target.segmentId(),
                        "换成室内类似项目",
                        "保留时间段和路线节奏，把受天气影响的户外节点替换成室内或有遮蔽的项目。",
                        "SUBMIT_PATCH",
                        target.segmentId(),
                        PlanDelta.fromPatch(patch),
                        target.poiId() == null || target.poiId().isBlank() ? List.of() : List.of(target.poiId()),
                        null),
                new RepairOption(
                        "keep-weather-" + target.segmentId(),
                        "保留原计划",
                        "天气风险已提示，继续保留当前户外节点。",
                        "KEEP_PLAN",
                        target.segmentId(),
                        null,
                        target.poiId() == null || target.poiId().isBlank() ? List.of() : List.of(target.poiId()),
                        null));
    }

    private QueueDecision buildQueueDecision(String planId, SegmentSlot slot, String targetTime,
                                             List<CandidateProfile> checkedCandidates, PlanIntent intent) {
        if (checkedCandidates == null || checkedCandidates.isEmpty()) return null;

        // Original candidate is the first one in checkedCandidates (highest score)
        CandidateProfile originalCandidate = checkedCandidates.stream()
                .filter(c -> c != null && c.poi() != null)
                .findFirst()
                .orElse(null);
        if (originalCandidate == null) return null;

        int queueMinutes = originalCandidate.availability() == null ? queueThresholdMinutes + 1
                : Math.max(0, originalCandidate.availability().queueTimeMinutes());
        String reason = originalCandidate.poi().name() + " 当前预计排队约 " + queueMinutes
                + " 分钟，我不建议直接塞进行程，给你几种处理方式。";

        // Find potential alternative candidates (different from originalCandidate)
        List<CandidateProfile> otherCandidates = new ArrayList<>();
        
        // 1. Gather other already checked candidates
        for (CandidateProfile c : checkedCandidates) {
            if (c != null && c.poi() != null && !c.poi().poiId().equals(originalCandidate.poi().poiId())) {
                otherCandidates.add(c);
            }
        }

        // 2. If we don't have enough other candidates, fetch from database to find nearby alternatives
        if (otherCandidates.size() < 2) {
            try {
                List<PoiDto> fallbackPois = poiDatabase.searchByCategory(
                        originalCandidate.poi().category(), List.of(), defaultRadiusKm);
                
                int headcount = (intent != null && intent.headcount() > 0) ? intent.headcount() : 1;
                AvailabilityProvider availabilityProvider = planningToolOrchestrator.getAvailabilityProvider();
                
                if (fallbackPois != null && availabilityProvider != null) {
                    for (PoiDto poi : fallbackPois) {
                        if (poi == null || poi.poiId() == null || poi.poiId().equals(originalCandidate.poi().poiId())) {
                            continue;
                        }
                        // Check if already in otherCandidates
                        boolean alreadyChecked = false;
                        for (CandidateProfile c : otherCandidates) {
                            if (c.poi().poiId().equals(poi.poiId())) {
                                alreadyChecked = true;
                                break;
                            }
                        }
                        if (alreadyChecked) continue;

                        // Check availability for this fallback POI
                        try {
                            CheckResponse checkResp = availabilityProvider.checkAvailability(
                                    poi.poiId(), targetTime, headcount);
                            String rejectionReason = null;
                            if (checkResp != null) {
                                String status = checkResp.status() == null ? "UNKNOWN" : checkResp.status();
                                if ("SOLD_OUT".equalsIgnoreCase(status) || "UNKNOWN".equalsIgnoreCase(status) 
                                        || checkResp.queueTimeMinutes() > queueThresholdMinutes) {
                                    rejectionReason = status + "/" + checkResp.queueTimeMinutes() + "min";
                                }
                            } else {
                                checkResp = new CheckResponse(poi.poiId(), "UNKNOWN", queueThresholdMinutes + 1, false);
                                rejectionReason = "unknown status";
                            }
                            
                            CandidateProfile fallbackProfile = new CandidateProfile(
                                    poi, originalCandidate.phase(), originalCandidate.score() - 10.0, 
                                    List.of(), checkResp, List.of(), rejectionReason);
                            
                            otherCandidates.add(fallbackProfile);
                            if (otherCandidates.size() >= 5) {
                                break; // Limit to a reasonable number of checks
                            }
                        } catch (Exception e) {
                            log.warn("Failed to check fallback POI availability for {}", poi.poiId(), e);
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("Failed to search fallback POIs for category {}", originalCandidate.poi().category(), e);
            }
        }

        // Now select shortest queue candidate and alternative candidate
        // Shortest queue candidate: the one in otherCandidates with the minimum queue time
        CandidateProfile shortestQueueCandidate = otherCandidates.stream()
                .sorted(Comparator.comparingInt(c -> c.availability() == null
                        ? Integer.MAX_VALUE
                        : Math.max(0, c.availability().queueTimeMinutes())))
                .findFirst()
                .orElse(null);

        // Alternative candidate: the next best one, or the one with the highest score
        CandidateProfile alternativeCandidate = otherCandidates.stream()
                .filter(c -> shortestQueueCandidate == null || !c.poi().poiId().equals(shortestQueueCandidate.poi().poiId()))
                .sorted(Comparator.comparingDouble(CandidateProfile::score).reversed())
                .findFirst()
                .orElse(null);

        List<RepairOption> repairOptions = new ArrayList<>();
        List<ActionCard.ActionOption> cardOptions = new ArrayList<>();

        // Always show Option 1: "仍保留原店" using originalCandidate
        addQueueOption(repairOptions, cardOptions, "queue-keep", "仍保留原店：", originalCandidate,
                slot.phase(), true);

        // If we have a shortest queue candidate, show Option 2: "换成排队较短的"
        if (shortestQueueCandidate != null) {
            addQueueOption(repairOptions, cardOptions, "queue-shortest", "换成排队较短的：", shortestQueueCandidate,
                    slot.phase(), false);
        }

        // If we have another alternative candidate, show Option 3: "换附近备选"
        if (alternativeCandidate != null) {
            addQueueOption(repairOptions, cardOptions, "queue-alternative", "换附近备选：", alternativeCandidate,
                    slot.phase(), false);
        }

        Conflict conflict = new Conflict(
                "QUEUE_CONFLICT",
                "MEDIUM",
                List.of("PENDING-" + slot.phase() + "-" + targetTime),
                reason,
                repairOptions);
        ActionCard card = new ActionCard(
                "queue-conflict-" + planId + "-" + slot.phase().toLowerCase(Locale.ROOT),
                "排队过长，选择处理方式",
                reason,
                cardOptions,
                "也可以直接说：换轻食、少排队、仍保留原店",
                true,
                "QUEUE_REPAIR");
        return new QueueDecision(conflict, repairOptions, card);
    }

    private void addQueueOption(List<RepairOption> repairOptions,
                                List<ActionCard.ActionOption> cardOptions,
                                String idPrefix,
                                String labelPrefix,
                                CandidateProfile candidate,
                                String phase,
                                boolean acceptQueue) {
        PoiDto poi = candidate.poi();
        int queueMinutes = candidate.availability() == null ? queueThresholdMinutes + 1
                : Math.max(0, candidate.availability().queueTimeMinutes());
        PoiPreview preview = new PoiPreview(poi.poiId(), poi.name(), poi.category(), poi.distanceKm(),
                poi.tags(), poi.address(), poi.businessHours(), poi.telephone(), poi.source(), "merchant-placeholder");
        PlanPatch patch = queuePatch(phase, poi.poiId(), acceptQueue ? queueMinutes : null);
        String optionId = idPrefix + "-" + poi.poiId();
        String description = "QUEUE_TOO_LONG；预计排队 " + queueMinutes + " 分钟，距离约 "
                + String.format(Locale.ROOT, "%.1fkm", poi.distanceKm()) + "。";
        RepairOption repairOption = new RepairOption(optionId, labelPrefix + poi.name(), description,
                "SUBMIT_PATCH", null, PlanDelta.fromPatch(patch), List.of(poi.poiId()), preview);
        repairOptions.add(repairOption);
        cardOptions.add(new ActionCard.ActionOption(optionId, labelPrefix + poi.name(), description,
                "SUBMIT_PATCH", null, null, patch, List.of(poi.poiId()), preview, "POI"));
    }

    private PlanPatch queuePatch(String phase, String poiId, Integer acceptedQueueMinutes) {
        List<String> prefer = new ArrayList<>();
        prefer.add("SELECTED_POI:" + poiId);
        prefer.add("QUEUE_REPAIR");
        if (acceptedQueueMinutes != null) {
            prefer.add("QUEUE_ACCEPTED:" + acceptedQueueMinutes);
        }
        return new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), prefer, null, null, null, false),
                true);
    }

    private boolean isOutdoorStep(PlanStep step) {
        if (step == null || step.poiId() == null || step.poiId().isBlank()) return false;
        return poiDatabase.findById(step.poiId())
                .map(poi -> match(normalizedTags(poi), "outdoor", "citywalk", "sports") > 0
                        && match(normalizedTags(poi), "indoor", "sheltered", "mall", "museum", "cafe") == 0)
                .orElse(false);
    }

    private List<PoiDto> searchCandidates(String category, String phase, PlanIntent intent, String requestPrompt,
                                           TraceRecorder trace, long deadlineAt) {
        trace.thought("开始检索 " + phase + " 候选，采用强偏好到弱偏好的有界兜底策略");

        // 提取 Prompt 与上下文中的显式指定商户 ID
        String fullPrompt = (intent.originalPrompt() == null ? "" : intent.originalPrompt())
                + " " + (requestPrompt == null ? "" : requestPrompt);
        List<String> specifiedIds = extractPoiIdsFromPrompt(fullPrompt);
        List<PoiDto> specifiedPois = new ArrayList<>();
        for (String id : specifiedIds) {
            Optional<PoiDto> poiOpt = poiDatabase.findById(id);
            if (poiOpt.isPresent()) {
                PoiDto poi = poiOpt.get();
                if (category.equalsIgnoreCase(poi.category())) {
                    specifiedPois.add(poi);
                }
            }
        }

        if (!specifiedPois.isEmpty()) {
            trace.thought("检测到用户显式指定的 " + phase + " 商户，优先选用：" + specifiedPois.stream().map(PoiDto::name).toList());
            return specifiedPois;
        }

        LinkedHashMap<String, PoiDto> merged = new LinkedHashMap<>();
        int baseRadius = "WIDE".equalsIgnoreCase(intent.locationScope()) ? maxRadiusKm : defaultRadiusKm;
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode())) {
            baseRadius = Math.min(baseRadius, Math.max(1, defaultRadiusKm));
        } else if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) {
            baseRadius = Math.max(baseRadius, maxRadiusKm);
        }
        List<SearchSpec> specs = buildSearchSpecs(category, phase, intent, baseRadius);

        for (SearchSpec spec : specs) {
            if (isDeadlineClose(deadlineAt)) break;
            ToolResult<String> result = callTool("searchNearby", Map.of(
                    "category", category,
                    "tags", spec.tags(),
                    "radiusKm", spec.radiusKm()), trace);
            if (!result.success()) {
                throw new AgentPlanningException(result.errorMessage());
            }
            for (PoiDto poi : parseSearchResults(result)) {
                if (isAllowedForIntent(poi, intent)) {
                    merged.putIfAbsent(poi.poiId(), poi);
                }
            }
            if (merged.size() >= maxChecksPerCategory) break;
        }

        List<PoiDto> candidates = new ArrayList<>(merged.values());
        candidates.sort((a, b) -> Double.compare(scorePoi(b, phase, intent), scorePoi(a, phase, intent)));
        trace.thought(phase + " 候选检索完成，共 " + candidates.size() + " 个可用候选进入打分队列");
        return candidates;
    }

    private List<String> extractPoiIdsFromPrompt(String prompt) {
        List<String> ids = new ArrayList<>();
        if (prompt == null) return ids;
        Matcher m = Pattern.compile("(P\\d{3}|H\\d{3}|S\\d{3}|B0[0-9A-Z]{8,})").matcher(prompt);
        while (m.find()) {
            ids.add(m.group());
        }
        return ids;
    }

    private List<SearchSpec> buildSearchSpecs(String category, String phase, PlanIntent intent, int baseRadius) {
        int expandedRadius = Math.max(baseRadius, maxRadiusKm);
        List<String> strongTags;
        List<String> weakTags;
        String prompt = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);

        if ("DRINKS".equals(phase)) {
            if (contains(prompt, "club", "蹦迪", "夜店")) {
                strongTags = List.of("club", "nightclub", "dance", "late_night");
                weakTags = List.of("livehouse", "bar", "nightlife", "cocktail");
            } else if (contains(prompt, "安静", "清吧", "不吵")) {
                strongTags = List.of("quiet_bar", "wine", "cocktail", "solo_friendly");
                weakTags = List.of("bar", "craft_beer", "drinks", "nightlife");
            } else {
                strongTags = List.of("bar", "drinks", "cocktail", "pub", "wine", "nightlife", "social_dining");
                weakTags = List.of("craft_beer", "quiet_bar", "social_dining", "casual", "party");
            }
        } else if ("RESTAURANT".equals(category)) {
            if (contains(prompt, "冰沙", "果汁", "奶茶", "甜品", "咖啡")) {
                strongTags = List.of("smoothie", "dessert", "juice", "tea", "coffee");
                weakTags = List.of("quick_bite", "solo_friendly", "casual");
            } else if (contains(prompt, "烧烤", "烤串", "bbq")) {
                strongTags = List.of("bbq", "late_night", "social_dining");
                weakTags = List.of("spicy", "street_food", "casual", "party");
            } else if (contains(prompt, "吃辣", "辣", "川菜", "湘菜", "火锅", "小龙虾") && !hasConstraint(intent, "NO_SPICY")) {
                strongTags = List.of("spicy", "sichuan", "hunan", "hotpot", "crayfish");
                weakTags = List.of("bbq", "late_night", "social_dining", "party");
            } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
                strongTags = List.of("social_dining", "quiet", "romantic");
                weakTags = List.of("dessert", "coffee", "wine", "bistro", "casual");
            } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                strongTags = List.of("social_dining");
                weakTags = List.of("party", "casual", "hotpot", "street_food", "normal", "bbq");
            } else {
                strongTags = List.of("dietary_type=light", "healthy", "vegan", "quick_bite", "family_style");
                weakTags = List.of("healthy", "family_style", "normal", "chinese", "quiet");
            }
        } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
            strongTags = List.of("quiet_bar", "dessert", "coffee", "photo", "exhibition");
            weakTags = List.of("citywalk", "movie", "wine", "romantic", "solo_friendly");
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            strongTags = List.of("social_entertainment");
            weakTags = List.of("exhibition", "citywalk", "movie", "team", "photo", "indoor", "outdoor");
        } else if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
            strongTags = List.of("child_friendly");
            weakTags = List.of("indoor", "outdoor", "science", "sports", "free");
        } else {
            strongTags = List.of("solo_friendly", "quiet_bar", "coffee");
            weakTags = List.of("citywalk", "casual", "tea", "museum", "bookstore");
        }

        if (intent.hasChildren()) {
            strongTags = mergeTags(List.of("child_friendly", "indoor", "science", "sports"), strongTags);
            weakTags = mergeTags(List.of("family_style", "free"), weakTags);
        }
        if (intent.weatherSensitive()) {
            strongTags = mergeTags(List.of("indoor"), strongTags);
        }
        if ("LOW".equalsIgnoreCase(intent.budgetLevel())) {
            weakTags = mergeTags(List.of("free", "quick_bite", "casual"), weakTags);
        }
        if (!intent.mustHave().isEmpty()) {
            strongTags = mergeTags(intent.mustHave(), strongTags);
        }

        List<SearchSpec> specs = new ArrayList<>();
        specs.add(new SearchSpec(strongTags, baseRadius));
        specs.add(new SearchSpec(weakTags, baseRadius));
        if (expandedRadius > baseRadius) {
            specs.add(new SearchSpec(strongTags, expandedRadius));
            specs.add(new SearchSpec(weakTags, expandedRadius));
        }
        specs.add(new SearchSpec(List.of(), expandedRadius));
        return specs;
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private List<String> mergeTags(List<String> preferred, List<String> existing) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (preferred != null) merged.addAll(preferred);
        if (existing != null) merged.addAll(existing);
        return List.copyOf(merged);
    }

    private Selection selectAvailable(String category, List<PoiDto> candidates, PlanIntent intent, String targetTime,
                                      TraceRecorder trace, long deadlineAt) {
        if (candidates.isEmpty()) {
            return Selection.none();
        }

        int checks = 0;
        List<String> rejected = new ArrayList<>();

        for (PoiDto candidate : candidates) {
            if (checks >= maxChecksPerCategory || isDeadlineClose(deadlineAt)) break;
            checks++;

            ToolResult<String> result = callTool("checkAvailability", Map.of(
                    "poiId", candidate.poiId(),
                    "targetTime", targetTime,
                    "headcount", safeHeadcount(intent)), trace);
            CheckResponse availability = parseCheckResponse(result, candidate.poiId());

            if (isAcceptable(availability)) {
                trace.thought(candidate.name() + " 实时状态可接受，进入方案");
                return new Selection(candidate, availability, false, null);
            }

            rejected.add(candidate.name() + "(" + availability.status() + "/" + availability.queueTimeMinutes() + "min)");
            trace.thought(candidate.name() + " 不满足实时约束，跳过并检查下一个候选");
        }

        String note = "可用候选不足，已跳过该节点；被跳过候选：" + String.join("、", rejected);
        trace.thought(note);
        return new Selection(null, null, true, note);
    }

    private boolean isAcceptable(CheckResponse availability) {
        String status = availability.status() == null ? "UNKNOWN" : availability.status();
        if ("SOLD_OUT".equalsIgnoreCase(status) || "UNKNOWN".equalsIgnoreCase(status)) return false;
        return availability.queueTimeMinutes() <= queueThresholdMinutes;
    }

    private ToolResult<String> callTool(String toolName, Map<String, Object> params, TraceRecorder trace) {
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            json = "{}";
        }

        trace.action(toolName, json);
        ToolResult<String> result = toolRegistry.execute(toolName, json);
        trace.observation(result.success() ? result.resultJson() : result.errorMessage());
        return result;
    }

    private List<PoiDto> parseSearchResults(ToolResult<String> result) {
        if (!result.success() || result.resultJson() == null) return List.of();
        try {
            SearchResponse response = objectMapper.readValue(result.resultJson(), SearchResponse.class);
            return response.results() == null ? List.of() : response.results();
        } catch (Exception e) {
            return List.of();
        }
    }

    private CheckResponse parseCheckResponse(ToolResult<String> result, String poiId) {
        if (!result.success() || result.resultJson() == null) {
            return new CheckResponse(poiId, "UNKNOWN", queueThresholdMinutes + 1, false);
        }
        try {
            return objectMapper.readValue(result.resultJson(), CheckResponse.class);
        } catch (Exception e) {
            return new CheckResponse(poiId, "UNKNOWN", queueThresholdMinutes + 1, false);
        }
    }

    private double scorePoi(PoiDto poi, String phase, PlanIntent intent) {
        double distancePenalty = "WALK".equalsIgnoreCase(intent.preferredTransportMode()) ? 18.0
                : "DRIVE".equalsIgnoreCase(intent.preferredTransportMode()) ? 5.0 : 10.0;
        double score = 100.0 - poi.distanceKm() * distancePenalty;
        int targetDuration = preferredDuration(phase, intent);
        score -= Math.abs(poi.recommendedDurationMinutes() - targetDuration) * 0.2;

        Set<String> tags = normalizedTags(poi);
        String prompt = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
        if (matchesIntentTerms(poi, intent.avoid())) {
            score -= 120;
        }
        if (matchesIntentTerms(poi, intent.mustHave())) {
            score += 45;
        }
        if ("LOW".equalsIgnoreCase(intent.budgetLevel())) {
            score += match(tags, "free", "quick", "casual") * 18;
            score -= match(tags, "premium", "fine", "cocktail") * 12;
        } else if ("HIGH".equalsIgnoreCase(intent.budgetLevel())) {
            score += match(tags, "cocktail", "wine", "exhibition", "premium") * 12;
        }
        if (intent.weatherSensitive()) {
            score += match(tags, "indoor") * 25;
            score -= match(tags, "outdoor", "citywalk") * 30;
        }
        if (intent.hasChildren()) {
            score += match(tags, "child", "family", "science", "sports", "indoor") * 22;
            score -= match(tags, "club", "nightclub", "adult") * 80;
        }
        if ("DRINKS".equals(phase)) {
            score += match(tags, "bar", "drink", "cocktail", "pub", "wine", "night", "social", "casual") * 18;
            if (contains(prompt, "安静", "清吧", "不吵")) {
                score += match(tags, "quiet", "wine", "solo") * 28;
                score -= match(tags, "club", "dance", "livehouse") * 45;
            }
            if (contains(prompt, "club", "蹦迪", "夜店")) {
                score += match(tags, "club", "dance", "nightclub") * 35;
            }
        } else if ("DINING".equals(phase)) {
            if (contains(prompt, "冰沙", "果汁", "奶茶", "甜品", "咖啡")) {
                score += match(tags, "smoothie", "juice", "tea", "dessert", "coffee") * 35;
            }
            if (contains(prompt, "烧烤", "烤串", "bbq")) {
                score += match(tags, "bbq", "grill", "late", "street") * 35;
            }
            if (contains(prompt, "吃辣", "辣", "川菜", "湘菜", "火锅", "小龙虾") && !hasConstraint(intent, "NO_SPICY")) {
                score += match(tags, "spicy", "sichuan", "hunan", "hotpot", "crayfish") * 32;
            }
            if ("DATE".equalsIgnoreCase(intent.sceneType())) {
                score += match(tags, "quiet", "romantic", "photo", "dessert", "wine", "bistro") * 20;
                score -= match(tags, "child", "family", "sports", "club") * 30;
            } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                score += match(tags, "social", "party", "hotpot", "street", "casual") * 16;
            } else {
                score += match(tags, "light", "healthy", "vegan", "quick", "family", "quiet") * 14;
            }
            if (hasConstraint(intent, "NO_SPICY")) {
                score -= match(tags, "spicy", "hotpot", "辣", "川湘") * 60;
                score += match(tags, "cantonese", "light", "healthy", "normal", "family", "quiet") * 12;
            }
        } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "quiet", "romantic", "photo", "dessert", "wine", "exhibition", "movie", "citywalk") * 20;
            score -= match(tags, "child", "family", "sports", "club", "dance") * 40;
            if (tags.contains("adult_only")) score += 20;
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "social", "exhibition", "citywalk", "movie", "team", "photo") * 15;
        } else if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "child", "indoor", "science", "sports", "free", "outdoor") * 14;
            if (tags.contains("adult_only")) score -= 100;
        } else {
            score += match(tags, "solo_friendly", "quiet", "coffee", "citywalk", "museum", "bookstore") * 15;
            if (tags.contains("adult_only")) score += 10;
        }
        return score;
    }

    private int match(Set<String> tags, String... needles) {
        int count = 0;
        for (String needle : needles) {
            for (String tag : tags) {
                if (tag.contains(needle)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private boolean matchesIntentTerms(PoiDto poi, List<String> terms) {
        if (poi == null || terms == null || terms.isEmpty()) return false;
        String name = poi.name() == null ? "" : poi.name().toLowerCase(Locale.ROOT);
        Set<String> tags = normalizedTags(poi);
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String normalized = term.toLowerCase(Locale.ROOT).trim();
            if (name.contains(normalized)) return true;
            for (String tag : tags) {
                if (tag.contains(normalized) || normalized.contains(tag)) return true;
            }
        }
        return false;
    }

    private Set<String> normalizedTags(PoiDto poi) {
        Set<String> tags = new HashSet<>();
        if (poi.tags() == null) return tags;
        for (String tag : poi.tags()) {
            tags.add(tag == null ? "" : tag.toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    private boolean isAllowedForIntent(PoiDto poi, PlanIntent intent) {
        if (poi == null) return false;
        if (!"SOCIAL".equalsIgnoreCase(intent.sceneType()) && !"DATE".equalsIgnoreCase(intent.sceneType())
                && normalizedTags(poi).contains("adult_only")) return false;
        if (intent.hasChildren() && normalizedTags(poi).contains("adult_only")) return false;
        
        // SOCIAL/DATE 场景且无儿童时，排除纯儿童设施
        if (("SOCIAL".equalsIgnoreCase(intent.sceneType()) || "DATE".equalsIgnoreCase(intent.sceneType()))
                && !intent.hasChildren()
                && isChildOnlyVenue(poi)) {
            return false;
        }

        if (matchesIntentTerms(poi, intent.avoid())) return false;
        if (hasConstraint(intent, "NO_SPICY") && match(normalizedTags(poi), "spicy", "hotpot", "辣", "川湘") > 0) {
            return false;
        }
        return true;
    }

    private boolean isChildOnlyVenue(PoiDto poi) {
        Set<String> tags = normalizedTags(poi);
        // 含有 child/children/kids/儿童 标签且不含 adult/social/bar 标签的场所
        boolean hasChildTag = tags.stream().anyMatch(t -> t.contains("child") || t.contains("kids") || t.contains("儿童"));
        boolean hasSocialTag = tags.stream().anyMatch(t -> t.contains("social") || t.contains("adult") || t.contains("bar"));
        return hasChildTag && !hasSocialTag;
    }

    private PlanStep buildPlanStep(Selection selection, SegmentSlot slot, PlanIntent intent, OrderIntent orderIntent) {
        PoiDto poi = selection.poi();
        String bookingStatus = orderIntent == null ? "无需预约" : "待确认";
        return new PlanStep(
                slot.durationMinutes(),
                slot.startTime(),
                slot.endTime(),
                slot.phase(),
                buildAction(poi, slot.phase(), intent),
                poi.poiId(),
                poi.name(),
                bookingStatus,
                planningNote(selection, orderIntent),
                new double[]{poi.lng(), poi.lat()},
                audience(intent),
                buildReason(poi, intent, selection.degraded()),
                "RESTAURANT".equals(poi.category()) ? "预计 CNY 60-120/人" : "预计 CNY 60-100/人",
                safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()),
                "PENDING_CONFIRMATION",
                orderIntent == null ? "" : orderIntent.orderIntentId(),
                poi.source(),
                poi.address(),
                poi.telephone(),
                poi.businessHours(),
                poi.typeCode(),
                ""
        );
    }

    private PlanStep buildTransitStep(PlanStep previousStep, PoiDto nextPoi, int startMinutes, PlanIntent intent) {
        if (previousStep.lnglat() == null || previousStep.lnglat().length < 2) return null;
        double distanceKm = GeoUtils.distanceKm(previousStep.lnglat()[0], previousStep.lnglat()[1], nextPoi.lng(), nextPoi.lat());
        int duration = estimateTransitMinutes(distanceKm, intent);
        String mode = transportMode(distanceKm, duration, intent);
        String startTime = formatMinutes(startMinutes);
        String endTime = formatMinutes(startMinutes + duration);
        String action = mode + " " + duration + " 分钟";
        return new PlanStep(
                duration,
                startTime,
                endTime,
                "TRANSIT",
                action,
                "",
                previousStep.poiName() + " → " + nextPoi.name(),
                "路上",
                String.format(Locale.ROOT, "%s约 %.1fkm，预计 %d 分钟。", mode, distanceKm, duration),
                new double[]{nextPoi.lng(), nextPoi.lat()},
                "路线衔接",
                String.format(Locale.ROOT, "从 %s 到 %s；交通时间不会挤占停留时间。", previousStep.poiName(), nextPoi.name()),
                "交通约 CNY 0-8",
                previousStep.headcount(),
                "",
                "TRANSIT",
                "",
                true,
                mode,
                distanceKm,
                previousStep.poiName(),
                nextPoi.name(),
                nextPoi.source(),
                nextPoi.address(),
                nextPoi.telephone(),
                nextPoi.businessHours(),
                nextPoi.typeCode()
        );
    }

    private PlanStep buildBufferStep(PlanIntent intent, int startMinutes, int endMinutes) {
        int duration = Math.max(0, endMinutes - startMinutes);
        return new PlanStep(
                duration,
                formatMinutes(startMinutes),
                formatMinutes(endMinutes),
                "LEISURE",
                "预留机动时间",
                "",
                "预留机动时间",
                "无需确认",
                "前面节点按真实停留和交通排好，尾段只保留少量机动时间。",
                null,
                audience(intent),
                "这是短尾巴时间，不作为可执行地点节点。",
                "可免费",
                safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()),
                "BUFFER",
                "",
                "system",
                "",
                "",
                "",
                "",
                ""
        );
    }

    private boolean shouldAddBuffer(PlanIntent intent, int bufferMinutes) {
        if (bufferMinutes < 20) return false;
        return bufferMinutes < 30 || wantsLooseBuffer(intent);
    }

    private boolean shouldExtendLastExecutableStep(PlanIntent intent, int remainingMinutes) {
        if (remainingMinutes <= 0 || remainingMinutes > 45) return false;
        return explicitlyWantsFixedEnd(intent) || remainingMinutes >= 30;
    }

    private boolean explicitlyWantsFixedEnd(PlanIntent intent) {
        String prompt = intent == null || intent.originalPrompt() == null
                ? ""
                : intent.originalPrompt().toLowerCase(Locale.ROOT);
        return "24:00".equals(intent == null ? "" : intent.endTime())
                || prompt.contains("24:00")
                || prompt.contains("12");
    }

    private boolean wantsLooseBuffer(PlanIntent intent) {
        String prompt = intent == null || intent.originalPrompt() == null
                ? ""
                : intent.originalPrompt().toLowerCase(Locale.ROOT);
        return "RELAXED".equalsIgnoreCase(intent == null ? "" : intent.pace())
                || prompt.contains("loose")
                || prompt.contains("buffer");
    }

    private PlanStep extendStepTo(PlanStep step, int endMinutes) {
        int startMinutes = toMinutes(step.startTime());
        int duration = Math.max(step.durationMinutes(), endMinutes - startMinutes);
        String note = step.note() == null || step.note().isBlank()
                ? "Tail time merged into this executable stop; no free buffer added."
                : step.note() + " Tail time merged into this executable stop; no free buffer added.";
        return new PlanStep(duration, step.startTime(), formatMinutes(endMinutes), step.phase(), step.action(),
                step.poiId(), step.poiName(), step.bookingStatus(), note, step.lnglat(), step.audience(),
                step.reason(), step.budget(), step.headcount(), step.constraints(), step.executionStatus(),
                step.orderIntentId(), step.isTransit(), step.transportMode(), step.distanceKm(), step.fromPoiName(),
                step.toPoiName(), step.source(), step.address(), step.telephone(), step.businessHours(),
                step.typeCode(), step.segmentId());
    }

    private void replaceLastBusinessStep(List<PlanStep> timeline, PlanStep previous, PlanStep replacement) {
        for (int i = timeline.size() - 1; i >= 0; i--) {
            PlanStep current = timeline.get(i);
            if (current == previous || Objects.equals(current.segmentId(), previous.segmentId())) {
                timeline.set(i, replacement);
                return;
            }
        }
    }

    private boolean isBufferStep(PlanStep step) {
        return step != null && "BUFFER".equalsIgnoreCase(step.executionStatus());
    }

    private String buildAction(PoiDto poi, String phase, PlanIntent intent) {
        return switch (phase) {
            case "DINING" -> hasConstraint(intent, "NO_SPICY") ? "不辣友好用餐" : "轻松用餐安排";
            case "DRINKS" -> "夜间小酌 / Bar";
            case "LEISURE" -> "轻松收尾活动";
            default -> "活动安排";
        };
    }

    private String buildReason(PoiDto poi, PlanIntent intent, boolean degraded) {
        String base = String.format(Locale.ROOT, "距离约 %.1fkm，停留约 %d 分钟，标签：%s",
                poi.distanceKm(), poi.recommendedDurationMinutes(), String.join("、", safeTags(poi)));
        if (hasConstraint(intent, "NO_SPICY") && "RESTAURANT".equals(poi.category())) {
            base += "；已避开辣味/火锅/川湘倾向。";
        }
        if (degraded) {
            return base + "；因实时约束不足，已作为降级可确认节点。";
        }
        return base + ("SOLO".equalsIgnoreCase(intent.sceneType()) ? "，适合一个人自由安排。" : "，适合低压力出行。");
    }

    private List<String> safeTags(PoiDto poi) {
        return poi.tags() == null || poi.tags().isEmpty() ? List.of("通用") : poi.tags();
    }

    private String buildSummary(PlanIntent intent, List<PlanStep> timeline, boolean degraded) {
        List<PlanStep> businessSteps = timeline.stream()
                .filter(step -> !step.isTransit())
                .filter(step -> !isBufferStep(step))
                .toList();
        timeline = businessSteps;
        StringBuilder sb = new StringBuilder();
        sb.append(intent.startTime()).append("-").append(intent.endTime())
                .append("，").append(safeHeadcount(intent)).append("人，已生成 ")
                .append(timeline.size()).append(" 个可执行节点：");
        for (int i = 0; i < timeline.size(); i++) {
            PlanStep step = timeline.get(i);
            if (i > 0) sb.append(" → ");
            sb.append(step.startTime()).append(" ").append(step.poiName()).append("（")
                    .append(step.durationMinutes()).append("分钟）");
        }
        if (!intent.dietaryConstraints().isEmpty()) {
            sb.append("。已考虑约束：").append(String.join("、", intent.dietaryConstraints()));
        }
        if (degraded) sb.append("。部分节点已按可执行优先做降级提示。");
        return sb.toString();
    }

    private String buildNotification(PlanIntent intent, List<PlanStep> timeline, boolean degraded) {
        String contact = "SOLO".equalsIgnoreCase(intent.sceneType()) ? "今晚" : "大家";
        StringBuilder sb = new StringBuilder(contact).append("，计划定好了：")
                .append(intent.startTime()).append(" 出发");
        for (PlanStep step : timeline) {
            if (isBufferStep(step)) continue;
            sb.append("，").append(step.startTime()).append(" ").append(step.poiName());
        }
        if (degraded) sb.append("。个别环节建议出发前再确认一下。");
        return sb.toString();
    }

    private String describeIntent(PlanIntent intent) {
        return intent.sceneType()
                + "，人数 " + safeHeadcount(intent)
                + "，时间 " + intent.startTime() + "-" + intent.endTime()
                + "，节点 " + String.join("/", intent.requestedSegments());
    }

    private int safeHeadcount(PlanIntent intent) {
        return intent.headcount() > 0 ? intent.headcount() : 1;
    }

    private boolean isDeadlineClose(long deadlineAt) {
        return System.currentTimeMillis() > deadlineAt - 1500;
    }

    private int minutesBetween(String start, String end) {
        return Math.max(0, toMinutes(end) - toMinutes(start));
    }

    private int estimateTransitMinutes(double distanceKm, PlanIntent intent) {
        if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.max(8, (int) Math.round(distanceKm / 28.0 * 60) + 6);
        }
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.max(6, (int) Math.round(distanceKm / 4.5 * 60));
        }
        if (distanceKm <= 0.8) return Math.max(6, (int) Math.round(distanceKm / 4.5 * 60));
        if (distanceKm <= 2.2) return Math.max(12, (int) Math.round(distanceKm / 18.0 * 60) + 8);
        return Math.max(18, (int) Math.round(distanceKm / 24.0 * 60) + 10);
    }

    private String transportMode(double distanceKm, int durationMinutes, PlanIntent intent) {
        if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) return "打车/自驾";
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode()) && distanceKm <= 1.8) return "步行";
        if (distanceKm <= 0.8 && durationMinutes <= 14) return "步行";
        if (distanceKm <= 2.2) return "公交/地铁";
        return "地铁";
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String searchCategoryFor(String phase) {
        return "ACTIVITY".equals(phase) || "LEISURE".equals(phase) ? "ACTIVITY" : "RESTAURANT";
    }

    private List<SegmentSlot> allocateSlots(PlanIntent intent) {
        List<String> phases = new ArrayList<>();
        List<String> specifiedIds = extractPoiIdsFromPrompt(intent.originalPrompt());
        if (!specifiedIds.isEmpty()) {
            for (String id : specifiedIds) {
                java.util.Optional<PoiDto> poiOpt = poiDatabase.findById(id);
                if (poiOpt.isPresent()) {
                    PoiDto poi = poiOpt.get();
                    String phase = "ACTIVITY";
                    if ("RESTAURANT".equalsIgnoreCase(poi.category())) {
                        java.util.Set<String> tags = new java.util.HashSet<>();
                        if (poi.tags() != null) {
                            for (String t : poi.tags()) {
                                tags.add(t == null ? "" : t.toLowerCase(java.util.Locale.ROOT));
                            }
                        }
                        if (tags.contains("bar") || tags.contains("drinks") || tags.contains("club") 
                                || tags.contains("nightlife") || tags.contains("cocktail") || tags.contains("quiet_bar")) {
                            phase = "DRINKS";
                        } else {
                            phase = "DINING";
                        }
                    }
                    phases.add(phase);
                }
            }
        }

        if (phases.isEmpty()) {
            phases.addAll(intent.requestedSegments());
        }
        if (phases.isEmpty()) {
            phases.addAll(List.of("ACTIVITY", "DINING"));
        }

        List<SegmentSlot> slots = new ArrayList<>();
        int transitBufferPerSlot = 15; // 每个 slot 预留 of average transit time
        int totalAllocated = 0;

        for (String requestedPhase : phases) {
            String phase = normalizePhase(requestedPhase);
            int duration = preferredDuration(phase, intent);
            slots.add(new SegmentSlot(phase, "", "", duration));
            totalAllocated += duration + transitBufferPerSlot;
        }

        // 时间感知：如果剩余时间 >= 90 分钟（够安排一个完整活动 + 交通），自动追加 slot
        int remaining = intent.totalMinutes() - totalAllocated;
        String[] fillPhases = {"LEISURE", "DINING", "LEISURE"};
        int fillIndex = 0;
        int fillThreshold = "RELAXED".equalsIgnoreCase(intent.pace()) ? 120 : "COMPACT".equalsIgnoreCase(intent.pace()) ? 70 : 90;
        int maxSlots = "RELAXED".equalsIgnoreCase(intent.pace()) ? 4 : 6;
        while (remaining >= fillThreshold && slots.size() < maxSlots) {
            String phase = fillPhases[fillIndex % fillPhases.length];
            int duration = preferredDuration(phase, intent);
            slots.add(new SegmentSlot(phase, "", "", duration));
            remaining -= (duration + transitBufferPerSlot);
            fillIndex++;
        }

        return slots;
    }

    private int preferredDuration(String phase, PlanIntent intent) {
        int base = switch (phase) {
            case "DINING" -> 60;
            case "DRINKS" -> 75;
            case "LEISURE" -> 60;
            default -> 90;
        };
        if ("RELAXED".equalsIgnoreCase(intent.pace())) return Math.round(base * 1.15f);
        if ("COMPACT".equalsIgnoreCase(intent.pace())) return Math.max(35, Math.round(base * 0.8f));
        return base;
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return "LEISURE";
        String normalized = phase.toUpperCase(Locale.ROOT);
        if ("BAR".equals(normalized)) return "DRINKS";
        if ("EVENING".equals(normalized)) return "LEISURE";
        return normalized;
    }

    private String formatMinutes(int minutes) {
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60);
    }

    private OrderIntent buildOrderIntent(String planId, int index, PoiDto poi, SegmentSlot slot, PlanIntent intent) {
        if (poi.poiId() == null || poi.poiId().isBlank()) return null;
        String type = switch (slot.phase()) {
            case "DINING", "DRINKS" -> "RESERVE_TABLE";
            case "ACTIVITY" -> "BOOK_TICKET";
            default -> "CHECK_ONLY";
        };
        if ("CHECK_ONLY".equals(type)) return null;
        return new OrderIntent("OI-" + planId + "-" + index, type, poi.poiId(), poi.name(),
                safeHeadcount(intent), slot.startTime(), "PENDING");
    }

    private String planningNote(Selection selection, OrderIntent orderIntent) {
        String realtime = selection.availability() == null
                ? "实时状态需人工确认"
                : "实时排队 " + selection.availability().queueTimeMinutes() + " 分钟";
        if (orderIntent == null) return realtime + "，此节点无需下单。";
        return realtime + "，确认方案后将模拟执行：" + orderIntent.type() + "。";
    }

    private boolean hasConstraint(PlanIntent intent, String constraint) {
        return intent.dietaryConstraints() != null
                && intent.dietaryConstraints().stream().anyMatch(c -> c.equalsIgnoreCase(constraint));
    }

    private String audience(PlanIntent intent) {
        if ("SOLO".equalsIgnoreCase(intent.sceneType())) return "一个人";
        if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) return "朋友小组";
        return "家庭 / 同行人";
    }

    private PlanIntent mergeIntents(PlanIntent original, PlanIntent newIntent) {
        // 仅当用户新 prompt 中显式提及人数/参与者时才覆盖原始人数
        String newLower = newIntent.originalPrompt() != null ? newIntent.originalPrompt().toLowerCase(Locale.ROOT) : "";
        boolean newMentionsHeadcount = contains(newLower, "人", "位", "独自", "朋友", "老婆", "孩子", "情侣", "聚会", "个人");
        int headcount = newMentionsHeadcount ? newIntent.headcount() : original.headcount();

        List<String> participants = newIntent.participants() != null && !newIntent.participants().isEmpty()
                ? newIntent.participants() : original.participants();

        String sceneType = newIntent.sceneType() != null && !newIntent.sceneType().isBlank()
                ? newIntent.sceneType() : original.sceneType();

        boolean hasTimeKeyword = contains(newIntent.originalPrompt(), "点", "时间", "时", "分", "延", "改", "HH:mm");
        String startTime = hasTimeKeyword ? newIntent.startTime() : original.startTime();
        String endTime = hasTimeKeyword ? newIntent.endTime() : original.endTime();
        int totalMinutes = hasTimeKeyword ? newIntent.totalMinutes() : original.totalMinutes();

        List<String> requestedSegments = newIntent.requestedSegments() != null && !newIntent.requestedSegments().isEmpty()
                ? newIntent.requestedSegments() : original.requestedSegments();

        List<String> dietaryConstraints = newIntent.dietaryConstraints() != null && !newIntent.dietaryConstraints().isEmpty()
                ? newIntent.dietaryConstraints() : original.dietaryConstraints();

        return new PlanIntent(headcount, participants, startTime, endTime, totalMinutes,
                sceneType, requestedSegments, dietaryConstraints,
                newIntent.drinkPreference(), newIntent.locationScope(), newIntent.originalPrompt(),
                contains(newLower, "别太赶", "不要太赶", "慢一点", "轻松", "松弛", "紧凑", "多安排", "赶一点", "relaxed", "compact")
                        ? newIntent.pace() : original.pace(),
                contains(newLower, "预算", "便宜", "省钱", "免费", "贵一点", "高级", "low budget", "high budget", "cheap", "premium")
                        ? newIntent.budgetLevel() : original.budgetLevel(),
                contains(newLower, "孩子", "小孩", "宝宝", "娃", "亲子", "儿童", "岁")
                        ? newIntent.hasChildren() : original.hasChildren(),
                contains(newLower, "孩子", "小孩", "宝宝", "娃", "亲子", "儿童", "岁")
                        ? newIntent.childAge() : original.childAge(),
                contains(newLower, "步行", "走路", "开车", "自驾", "打车", "公交", "地铁", "公共交通", "walk", "drive", "taxi", "metro", "subway")
                        ? newIntent.preferredTransportMode() : original.preferredTransportMode(),
                contains(newLower, "避开", "不要", "不想要", "别去", "别安排", "avoid")
                        ? newIntent.avoid() : original.avoid(),
                contains(newLower, "必须", "一定要", "想要", "最好有", "要有", "must")
                        ? newIntent.mustHave() : original.mustHave(),
                contains(newLower, "下雨", "雨天", "天气", "太晒", "怕晒", "怕冷", "室内", "weather", "rain", "indoors")
                        ? newIntent.weatherSensitive() : original.weatherSensitive(),
                false);
    }

    private record SearchSpec(List<String> tags, int radiusKm) {}

    private record SegmentSlot(String phase, String startTime, String endTime, int durationMinutes) {}

    private record QueueDecision(Conflict conflict, List<RepairOption> repairOptions, ActionCard actionCard) {}

    private record Selection(PoiDto poi, CheckResponse availability, boolean degraded, String degradationNote) {
        static Selection none() {
            return new Selection(null, null, true, "没有找到可用候选");
        }
    }

    private static class TraceRecorder {
        private final Consumer<SseEvent> emitter;
        private final PlanIntent intent;
        private final PlanNarrativeBuilder narrativeBuilder;
        private final List<WorkflowTrace> trace = new ArrayList<>();
        private int step = 0;

        TraceRecorder(Consumer<SseEvent> emitter, PlanIntent intent, PlanNarrativeBuilder narrativeBuilder) {
            this.emitter = emitter;
            this.intent = intent;
            this.narrativeBuilder = narrativeBuilder;
        }

        void start(String content) {
            emit(new SseEvent("START", 0, content, null, null, null, null, null, null, intent, null, null));
            emit(new SseEvent(WorkflowEventType.PLAN_STARTED, 0, content, null, null, null, null, null,
                    null, intent, null, null));
        }

        void thought(String content) {
            add("THOUGHT", content);
            emit(new SseEvent("THOUGHT", step, content, null, null, null, null, null, null, intent, null, null));
        }

        void action(String toolName, String params) {
            add("ACTION", "Tool: " + toolName + ", Params: " + truncate(params, 300));
            emit(new SseEvent("ACTION", step, toolName + ": " + truncate(params, 200), null, null, null, null, null, null, intent, null, null));
            String workflowType = toolName != null && toolName.contains("collectCandidates")
                    ? WorkflowEventType.CANDIDATES_SEARCHING : WorkflowEventType.AVAILABILITY_CHECKED;
            emit(new SseEvent(workflowType, step, toolName + ": " + truncate(params, 200), null,
                    null, null, null, null, null, intent, null, null));
        }

        void observation(String content) {
            add("OBSERVATION", truncate(content, 500));
            emit(new SseEvent("OBSERVATION", step, truncate(content, 300), null, null, null, null, null, null, intent, null, null));
            String workflowType = content != null && content.contains("candidatePool")
                    ? WorkflowEventType.CANDIDATES_FOUND : WorkflowEventType.AVAILABILITY_CHECKED;
            emit(new SseEvent(workflowType, step, truncate(content, 300), null, null, null, null,
                    null, null, intent, null, null));
        }

        void planStep(String content, List<PlanStep> timeline) {
            emit(new SseEvent("PLAN_STEP", step, content, List.copyOf(timeline), null, null, null, null, null, intent, null, null));
            emit(new SseEvent(WorkflowEventType.SEGMENT_PLANNED, step, content, List.copyOf(timeline),
                    null, null, null, null, null, intent, null, null));
        }

        void intent(String content, PlanIntent intent) {
            emit(new SseEvent("INTENT", step, content, null, null, null, null, null,
                    null, intent, null, null));
            emit(new SseEvent(WorkflowEventType.INTENT_EXTRACTED, step, content, null, null, null, null,
                    null, null, intent, null, null));
        }

        void narrative(String content) {
            emit(new SseEvent(WorkflowEventType.PLAN_NARRATIVE, step, content, null, null, null,
                    null, null, null, intent, null, null));
        }

        void weather(WeatherSnapshot weather) {
            String content = weather == null ? "weather unavailable"
                    : weather.city() + " " + weather.condition() + " " + weather.outdoorRiskLevel() + ": " + weather.summary();
            emit(new SseEvent(WorkflowEventType.WEATHER_CHECKED, step, content, null, null, null,
                    null, null, null, intent, null, null, null, null, null,
                    List.of(), List.of(), 1, PlanStatus.PENDING_CONFIRMATION, weather));
        }

        List<WorkflowTrace> finish(String summary) {
            add("FINISH", summary);
            return List.copyOf(trace);
        }

        void emitFinish(PlanResponse response) {
            emitFinish(response, null);
        }

        void emitFinish(PlanResponse response, ActionCard actionCard) {
            String finalBrief = narrativeBuilder.finalBrief(
                    response.intent(),
                    response.weather(),
                    response.timeline(),
                    response.degradationNote() != null && !response.degradationNote().isBlank(),
                    response.conflicts()
            );
            emit(new SseEvent(WorkflowEventType.PLAN_ASSEMBLED, step, finalBrief, response.timeline(),
                    response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                    response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                    null, null, null, response.conflicts(), response.repairOptions(),
                    response.version(), response.planStatus(), response.weather(), response.summary()));
            emit(new SseEvent(WorkflowEventType.PLAN_FINISHED, step, finalBrief, response.timeline(),
                    response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                    response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                    null, null, null, response.conflicts(), response.repairOptions(),
                    response.version(), response.planStatus(), response.weather(), response.summary()));
            emit(new SseEvent("FINISH", step, finalBrief, response.timeline(),
                    response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                    response.planId(), response.intent(), response.orderIntents(), response.executionStatus(),
                    null, actionCard, null, response.conflicts(), response.repairOptions(),
                    response.version(), response.planStatus(), response.weather(), response.summary()));
        }

        private void add(String type, String content) {
            step++;
            trace.add(new WorkflowTrace(step, type, content));
        }

        private void emit(SseEvent event) {
            if (emitter != null) emitter.accept(event);
        }

        private String truncate(String s, int max) {
            if (s == null) return "";
            return s.length() <= max ? s : s.substring(0, max) + "...";
        }
    }
}
