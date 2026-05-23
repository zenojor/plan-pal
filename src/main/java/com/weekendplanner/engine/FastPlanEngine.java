package com.weekendplanner.engine;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.*;
import com.weekendplanner.mock.GeoUtils;
import com.weekendplanner.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Consumer;

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
    private final ObjectMapper objectMapper;

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

    public FastPlanEngine(ToolRegistry toolRegistry,
                          IntentExtractor intentExtractor,
                          PlanExecutionStore executionStore,
                          ObjectMapper objectMapper) {
        this.toolRegistry = toolRegistry;
        this.intentExtractor = intentExtractor;
        this.executionStore = executionStore;
        this.objectMapper = objectMapper;
    }

    public PlanResponse executePlan(PlanRequest request) {
        return executePlanInternal(request, null);
    }

    public PlanResponse executePlanStreaming(PlanRequest request, Consumer<SseEvent> emitter) {
        return executePlanInternal(request, emitter);
    }

    private PlanResponse executePlanInternal(PlanRequest request, Consumer<SseEvent> emitter) {
        long deadlineAt = System.currentTimeMillis() + deadlineMs;
        String planId = UUID.randomUUID().toString().substring(0, 8);
        PlanIntent intent = intentExtractor.extract(request.prompt());
        TraceRecorder trace = new TraceRecorder(emitter);

        List<PlanStep> timeline = new ArrayList<>();
        List<OrderIntent> orderIntents = new ArrayList<>();
        List<String> degradationNotes = new ArrayList<>();

        log.info("[FastPlan] 开始 planId={}, scene={}, start={}, end={}, headcount={}",
                planId, intent.sceneType(), intent.startTime(), intent.endTime(), intent.headcount());
        trace.start("开始有界规划，目标是在 30 秒内生成可执行方案");
        trace.thought("已解析需求：" + describeIntent(intent));
        trace.intent("解析到时间 " + intent.startTime() + "-" + intent.endTime()
                + "，人数 " + safeHeadcount(intent)
                + "，约束 " + String.join("、", intent.dietaryConstraints()), intent);

        List<SegmentSlot> slots = allocateSlots(intent);
        int cursorMinutes = toMinutes(intent.startTime());
        int planEndMinutes = toMinutes(intent.endTime());
        PlanStep previousBusinessStep = null;

        for (SegmentSlot slot : slots) {
            if (isDeadlineClose(deadlineAt)) {
                degradationNotes.add("规划时间接近上限，后续节点已按可确认草案处理。");
                break;
            }

            String searchCategory = searchCategoryFor(slot.phase());
            String targetTime = formatMinutes(cursorMinutes);
            List<PoiDto> candidates = searchCandidates(searchCategory, slot.phase(), intent, trace, deadlineAt);
            Selection selection = selectAvailable(searchCategory, candidates, intent, targetTime, trace, deadlineAt);

            if (selection.poi() == null) {
                SegmentSlot timedSlot = new SegmentSlot(slot.phase(), targetTime, formatMinutes(cursorMinutes + slot.durationMinutes()), slot.durationMinutes());
                PlanStep fallback = buildManualFallbackStep(intent, timedSlot);
                timeline.add(fallback);
                previousBusinessStep = fallback;
                cursorMinutes += fallback.durationMinutes();
                degradationNotes.add("没有找到完全匹配的 " + slot.phase() + " 候选，已保留人工确认节点。");
                trace.planStep("已生成降级拼图：" + fallback.poiName(), timeline);
                continue;
            }

            if (selection.degraded()) degradationNotes.add(selection.degradationNote());
            if (previousBusinessStep != null) {
                PlanStep transitStep = buildTransitStep(previousBusinessStep, selection.poi(), cursorMinutes);
                if (transitStep != null) {
                    timeline.add(transitStep);
                    cursorMinutes += transitStep.durationMinutes();
                    trace.planStep("已补上交通拼图：" + transitStep.action(), timeline);
                }
            }

            SegmentSlot timedSlot = new SegmentSlot(slot.phase(), formatMinutes(cursorMinutes),
                    formatMinutes(cursorMinutes + slot.durationMinutes()), slot.durationMinutes());
            OrderIntent orderIntent = buildOrderIntent(planId, orderIntents.size() + 1, selection.poi(), timedSlot, intent);
            if (orderIntent != null) orderIntents.add(orderIntent);

            PlanStep step = buildPlanStep(selection, timedSlot, intent, orderIntent);
            timeline.add(step);
            previousBusinessStep = step;
            cursorMinutes += step.durationMinutes();
            trace.planStep("已确认草案拼图：" + step.poiName(), timeline);
        }

        if (timeline.isEmpty()) {
            timeline.add(buildManualFallbackStep(intent, new SegmentSlot("LEISURE", intent.startTime(), intent.endTime(),
                    Math.max(45, Math.min(60, intent.totalMinutes())))));
            trace.planStep("数据不足，已生成可人工确认的降级拼图", timeline);
        }

        if (previousBusinessStep != null && cursorMinutes < planEndMinutes) {
            int bufferMinutes = planEndMinutes - cursorMinutes;
            if (bufferMinutes >= 20) {
                PlanStep bufferStep = buildBufferStep(intent, cursorMinutes, planEndMinutes);
                timeline.add(bufferStep);
                trace.planStep("已补上自由缓冲拼图：" + bufferStep.action(), timeline);
            }
        }

        boolean degraded = !degradationNotes.isEmpty();
        String degradationNote = degraded ? String.join("；", degradationNotes) : null;
        String status = degraded ? "DEGRADED" : "SUCCESS";
        String summary = buildSummary(intent, timeline, degraded);
        String notificationText = buildNotification(intent, timeline, degraded);

        PlanResponse response = new PlanResponse(planId, request.userId(), status, summary,
                List.copyOf(timeline), trace.finish(summary), "", notificationText, degradationNote,
                intent, List.copyOf(orderIntents), "PENDING_CONFIRMATION");

        executionStore.save(new PlanExecutionStore.DraftPlan(planId, request.userId(), intent,
                List.copyOf(timeline), List.copyOf(orderIntents), notificationText));

        trace.emitFinish(response);
        log.info("[FastPlan] 完成 planId={}, status={}, steps={}, orderIntents={}",
                planId, status, timeline.size(), orderIntents.size());
        return response;
    }

    private List<PoiDto> searchCandidates(String category, String phase, PlanIntent intent,
                                           TraceRecorder trace, long deadlineAt) {
        trace.thought("开始检索 " + phase + " 候选，采用强偏好到弱偏好的有界兜底策略");
        LinkedHashMap<String, PoiDto> merged = new LinkedHashMap<>();
        int baseRadius = "WIDE".equalsIgnoreCase(intent.locationScope()) ? maxRadiusKm : defaultRadiusKm;
        List<SearchSpec> specs = buildSearchSpecs(category, phase, intent, baseRadius);

        for (SearchSpec spec : specs) {
            if (isDeadlineClose(deadlineAt)) break;
            ToolCallResult result = callTool("searchNearby", Map.of(
                    "category", category,
                    "tags", spec.tags(),
                    "radiusKm", spec.radiusKm()), trace);
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
            } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                strongTags = List.of("social_dining");
                weakTags = List.of("party", "casual", "hotpot", "street_food", "normal", "bbq");
            } else {
                strongTags = List.of("dietary_type=light", "healthy", "vegan", "quick_bite", "family_style");
                weakTags = List.of("healthy", "family_style", "normal", "chinese", "quiet");
            }
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            strongTags = List.of("social_entertainment");
            weakTags = List.of("exhibition", "citywalk", "movie", "team", "photo", "indoor", "outdoor");
        } else {
            strongTags = List.of("child_friendly");
            weakTags = List.of("indoor", "outdoor", "science", "sports", "free");
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

            ToolCallResult result = callTool("checkAvailability", Map.of(
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

        PoiDto fallback = candidates.get(0);
        String note = "可用候选不足，已选择评分最高的 " + fallback.name()
                + " 并提示人工确认；被跳过候选：" + String.join("、", rejected);
        trace.thought(note);
        return new Selection(fallback, null, true, note);
    }

    private boolean isAcceptable(CheckResponse availability) {
        String status = availability.status() == null ? "UNKNOWN" : availability.status();
        if ("SOLD_OUT".equalsIgnoreCase(status) || "UNKNOWN".equalsIgnoreCase(status)) return false;
        return availability.queueTimeMinutes() <= queueThresholdMinutes;
    }

    private ToolCallResult callTool(String toolName, Map<String, Object> params, TraceRecorder trace) {
        String json;
        try {
            json = objectMapper.writeValueAsString(params);
        } catch (JsonProcessingException e) {
            json = "{}";
        }

        trace.action(toolName, json);
        ToolCallResult result = toolRegistry.execute(toolName, json);
        trace.observation(result.success() ? result.resultJson() : result.errorMessage());
        return result;
    }

    private List<PoiDto> parseSearchResults(ToolCallResult result) {
        if (!result.success() || result.resultJson() == null) return List.of();
        try {
            SearchResponse response = objectMapper.readValue(result.resultJson(), SearchResponse.class);
            return response.results() == null ? List.of() : response.results();
        } catch (Exception e) {
            return List.of();
        }
    }

    private CheckResponse parseCheckResponse(ToolCallResult result, String poiId) {
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
        double score = 100.0 - poi.distanceKm() * 10.0;
        int targetDuration = switch (phase) {
            case "DINING" -> 75;
            case "DRINKS" -> 90;
            case "LEISURE" -> 60;
            default -> 90;
        };
        score -= Math.abs(poi.recommendedDurationMinutes() - targetDuration) * 0.2;

        Set<String> tags = normalizedTags(poi);
        String prompt = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
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
            if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                score += match(tags, "social", "party", "hotpot", "street", "casual") * 16;
            } else {
                score += match(tags, "light", "healthy", "vegan", "quick", "family", "quiet") * 14;
            }
            if (hasConstraint(intent, "NO_SPICY")) {
                score -= match(tags, "spicy", "hotpot", "辣", "川湘") * 60;
                score += match(tags, "cantonese", "light", "healthy", "normal", "family", "quiet") * 12;
            }
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "social", "exhibition", "citywalk", "movie", "team", "photo") * 15;
        } else {
            score += match(tags, "child", "indoor", "science", "sports", "free", "outdoor") * 14;
            if (tags.contains("adult_only")) score -= 100;
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
        if (!"SOCIAL".equalsIgnoreCase(intent.sceneType()) && normalizedTags(poi).contains("adult_only")) return false;
        if (hasConstraint(intent, "NO_SPICY") && match(normalizedTags(poi), "spicy", "hotpot", "辣", "川湘") > 0) {
            return false;
        }
        return true;
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
                orderIntent == null ? "" : orderIntent.orderIntentId()
        );
    }

    private PlanStep buildManualFallbackStep(PlanIntent intent, SegmentSlot slot) {
        return new PlanStep(
                slot.durationMinutes(),
                slot.startTime(),
                slot.endTime(),
                slot.phase(),
                "人工确认附近可用活动",
                "",
                "待定地点",
                "待确认",
                "当前模拟数据不足，建议电话确认附近可用场地后执行。",
                null,
                audience(intent),
                "保证在数据不足时仍给出可执行下一步，而不是规划失败。",
                "按现场确认",
                safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()),
                "PENDING_CONFIRMATION",
                ""
        );
    }

    private PlanStep buildTransitStep(PlanStep previousStep, PoiDto nextPoi, int startMinutes) {
        if (previousStep.lnglat() == null || previousStep.lnglat().length < 2) return null;
        double distanceKm = GeoUtils.distanceKm(previousStep.lnglat()[0], previousStep.lnglat()[1], nextPoi.lng(), nextPoi.lat());
        int duration = estimateTransitMinutes(distanceKm);
        String mode = transportMode(distanceKm, duration);
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
                nextPoi.name()
        );
    }

    private PlanStep buildBufferStep(PlanIntent intent, int startMinutes, int endMinutes) {
        int duration = Math.max(0, endMinutes - startMinutes);
        return new PlanStep(
                duration,
                formatMinutes(startMinutes),
                formatMinutes(endMinutes),
                "LEISURE",
                "自由缓冲 / 散步返程",
                "",
                "就近自由安排",
                "灵活收尾",
                "前面节点按真实停留和交通排好，剩余时间留给散步、排队缓冲或返程。",
                null,
                audience(intent),
                "不强行拉长餐饮或活动时间，保留真实节奏。",
                "可免费",
                safeHeadcount(intent),
                String.join("、", intent.dietaryConstraints()),
                "PENDING_CONFIRMATION",
                ""
        );
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

    private int estimateTransitMinutes(double distanceKm) {
        if (distanceKm <= 0.8) return Math.max(6, (int) Math.round(distanceKm / 4.5 * 60));
        if (distanceKm <= 2.2) return Math.max(12, (int) Math.round(distanceKm / 18.0 * 60) + 8);
        return Math.max(18, (int) Math.round(distanceKm / 24.0 * 60) + 10);
    }

    private String transportMode(double distanceKm, int durationMinutes) {
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
        List<String> phases = new ArrayList<>(intent.requestedSegments());
        if (phases.isEmpty()) phases.addAll(List.of("ACTIVITY", "DINING"));

        List<SegmentSlot> slots = new ArrayList<>();

        for (String requestedPhase : phases) {
            String phase = normalizePhase(requestedPhase);
            int duration = preferredDuration(phase);
            slots.add(new SegmentSlot(phase, "", "", duration));
        }
        return slots;
    }

    private int preferredDuration(String phase) {
        return switch (phase) {
            case "DINING" -> 60;
            case "DRINKS" -> 75;
            case "LEISURE" -> 60;
            default -> 90;
        };
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

    private record SearchSpec(List<String> tags, int radiusKm) {}

    private record SegmentSlot(String phase, String startTime, String endTime, int durationMinutes) {}

    private record Selection(PoiDto poi, CheckResponse availability, boolean degraded, String degradationNote) {
        static Selection none() {
            return new Selection(null, null, true, "没有找到可用候选");
        }
    }

    private static class TraceRecorder {
        private final Consumer<SseEvent> emitter;
        private final List<ReActTrace> trace = new ArrayList<>();
        private int step = 0;

        TraceRecorder(Consumer<SseEvent> emitter) {
            this.emitter = emitter;
        }

        void start(String content) {
            emit(new SseEvent("START", 0, content, null));
        }

        void thought(String content) {
            add("THOUGHT", content);
            emit(new SseEvent("THOUGHT", step, content, null));
        }

        void action(String toolName, String params) {
            add("ACTION", "Tool: " + toolName + ", Params: " + truncate(params, 300));
            emit(new SseEvent("ACTION", step, toolName + ": " + truncate(params, 200), null));
        }

        void observation(String content) {
            add("OBSERVATION", truncate(content, 500));
            emit(new SseEvent("OBSERVATION", step, truncate(content, 300), null));
        }

        void planStep(String content, List<PlanStep> timeline) {
            emit(new SseEvent("PLAN_STEP", step, content, List.copyOf(timeline)));
        }

        void intent(String content, PlanIntent intent) {
            emit(new SseEvent("INTENT", step, content, null, null, null, null, null,
                    null, intent, null, null));
        }

        List<ReActTrace> finish(String summary) {
            add("FINISH", summary);
            return List.copyOf(trace);
        }

        void emitFinish(PlanResponse response) {
            emit(new SseEvent("FINISH", step, response.summary(), response.timeline(),
                    response.status(), response.orderGroupId(), response.notificationText(), response.degradationNote(),
                    response.planId(), response.intent(), response.orderIntents(), response.executionStatus()));
        }

        private void add(String type, String content) {
            step++;
            trace.add(new ReActTrace(step, type, content));
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
