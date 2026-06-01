package com.weekendplanner.engine.patch;


import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.ReActTrace;
import com.weekendplanner.tool.ToolRegistry;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class PlanEditorEngine {

    private final PlanExecutionStore executionStore;
    private final TimelineAssembler timelineAssembler;
    private final ReplacementSearchEngine replacementSearchEngine;
    @SuppressWarnings("unused")
    private final ToolRegistry toolRegistry;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public PlanEditorEngine(PlanExecutionStore executionStore,
                            TimelineAssembler timelineAssembler,
                            ReplacementSearchEngine replacementSearchEngine,
                            ToolRegistry toolRegistry,
                            ObjectMapper objectMapper) {
        this.executionStore = executionStore;
        this.timelineAssembler = timelineAssembler;
        this.replacementSearchEngine = replacementSearchEngine;
        this.toolRegistry = toolRegistry;
        this.objectMapper = objectMapper;
    }

    public PlanResponse applyPatch(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return applyDelta(draft, PlanDelta.fromPatch(patch));
    }

    public PlanResponse applyDelta(PlanExecutionStore.DraftPlan draft, PlanDelta delta) {
        PlanPatch patch = delta == null || delta.patch() == null
                ? new PlanPatch("MODIFY_PLAN", "KEEP_AND_REPLAN", null, null, false)
                : delta.patch();
        PlanIntent updatedIntent = applyIntentRequirements(draft.intent(), patch);
        if (delta != null && delta.changedConstraints() != null && delta.changedConstraints().totalMinutes() != null) {
            updatedIntent = withTotalMinutes(updatedIntent, delta.changedConstraints().totalMinutes());
        }
        List<PlanStep> businessSteps = draft.timeline().stream()
                .filter(step -> step != null && !step.isTransit() && !"TRANSIT".equalsIgnoreCase(step.phase()))
                .filter(step -> (step.poiId() != null && !step.poiId().isBlank()) || (step.segmentId() != null && !step.segmentId().isBlank()))
                .map(this::stripOrderState)
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        List<PlanStep> edited = switch (patch.editType()) {
            case "RELAX" -> relax(businessSteps, patch);
            case "DELETE" -> deleteTargets(businessSteps, patch);
            case "REPLACE" -> replaceTarget(businessSteps, patch, updatedIntent);
            case "ADD" -> addStep(businessSteps, patch, updatedIntent);
            case "REORDER" -> reorderSteps(businessSteps, patch);
            case "TIGHTEN" -> tighten(businessSteps, patch, updatedIntent);
            case "TIME_SHIFT" -> timeShift(businessSteps, patch);
            default -> keepAndReplan(businessSteps, patch, updatedIntent);
        };

        if (edited.isEmpty()) edited = businessSteps;

        TimelineAssembler.Result rebuilt = timelineAssembler.assemble(draft.planId(), updatedIntent, edited,
                !patch.requirements().endEarlier(), 0);
        String summary = buildSummary(updatedIntent, rebuilt.timeline(), patch);
        String notificationText = buildNotification(updatedIntent, rebuilt.timeline(), patch);
        PlanExecutionStore.DraftPlan saved = draft.nextVersion(updatedIntent, rebuilt.timeline(),
                rebuilt.orderIntents(), notificationText);
        PlanResponse response = new PlanResponse(draft.planId(), draft.userId(), "SUCCESS", summary,
                rebuilt.timeline(), buildTrace(patch), "", notificationText, null,
                updatedIntent, rebuilt.orderIntents(), "PENDING_CONFIRMATION", saved.version(),
                PlanStatus.MODIFIED, List.of(), List.of(), null);

        executionStore.save(saved);
        return response;
    }

    private PlanIntent withTotalMinutes(PlanIntent intent, int totalMinutes) {
        int start = toMinutes(intent.startTime());
        String endTime = formatMinutes(start + Math.max(30, totalMinutes));
        return new PlanIntent(intent.headcount(), intent.participants(), intent.startTime(), endTime,
                Math.max(30, totalMinutes), intent.sceneType(), intent.requestedSegments(),
                intent.dietaryConstraints(), intent.drinkPreference(), intent.locationScope(),
                intent.originalPrompt(), intent.pace(), intent.budgetLevel(), intent.hasChildren(),
                intent.childAge(), intent.preferredTransportMode(), intent.avoid(), intent.mustHave(),
                intent.weatherSensitive(), intent.isConsultingMode());
    }

    private List<PlanStep> relax(List<PlanStep> steps, PlanPatch patch) {
        List<PlanStep> edited = new ArrayList<>(steps);
        Optional<PlanStep> removable = edited.stream()
                .filter(step -> !isKept(step, patch))
                .filter(step -> matchesTarget(step, patch))
                .filter(step -> !"DINING".equalsIgnoreCase(step.phase()))
                .max(Comparator.comparingInt(PlanStep::durationMinutes));

        if (removable.isPresent() && edited.size() > keptCount(edited, patch) + 1) {
            edited.remove(removable.get());
            return edited;
        }

        for (int i = 0; i < edited.size(); i++) {
            PlanStep step = edited.get(i);
            if (!isKept(step, patch) && matchesTarget(step, patch)) {
                edited.set(i, resizeStep(step, Math.max(35, Math.round(step.durationMinutes() * 0.7f))));
                break;
            }
        }
        if (patch.requirements().endEarlier()) return timeShift(edited, patch);
        return edited;
    }

    private List<PlanStep> deleteTargets(List<PlanStep> steps, PlanPatch patch) {
        List<PlanStep> edited = steps.stream()
                .filter(step -> isKept(step, patch) || !matchesTarget(step, patch))
                .toList();
        return edited.isEmpty() ? steps : new ArrayList<>(edited);
    }

    private List<PlanStep> replaceTarget(List<PlanStep> steps, PlanPatch patch, PlanIntent intent) {
        List<PlanStep> edited = new ArrayList<>(steps);
        int index = findTargetIndex(edited, patch);
        if (index < 0) {
            // 如果是替换但找不到对应目标节点（例如用户要“晚上”吃饭，但现有行程只有下午），
            // 且具备合法的目标时间段或阶段，则智能降级/转换为 ADD（新增节点）
            if (patch.target().timeRange() != null || patch.target().phase() != null || patch.target().activityType() != null) {
                return addStep(steps, patch, intent);
            }
            return edited;
        }

        PlanStep target = edited.get(index);
        if (isKept(target, patch)) return edited;

        Set<String> usedIds = new HashSet<>();
        edited.forEach(step -> usedIds.add(step.poiId()));
        String replacementPhase = normalizePhase(firstNonBlank(patch.target().activityType(), target.phase()));
        replacementSearchEngine.findReplacement(target, patch, intent, usedIds).ifPresent(poi -> {
            int duration = selectedDuration(patch).orElse(Math.min(target.durationMinutes(), poi.recommendedDurationMinutes()));
            edited.set(index, stepFromPoi(poi, replacementPhase, duration, intent, target.segmentId(), patch));
        });
        return edited;
    }

    private List<PlanStep> addStep(List<PlanStep> steps, PlanPatch patch, PlanIntent intent) {
        List<PlanStep> edited = new ArrayList<>(steps);
        String phase = normalizePhase(firstNonBlank(patch.target().phase(), patch.target().activityType(), inferPhaseFromPatch(patch)));
        Set<String> usedIds = new HashSet<>();
        edited.forEach(step -> usedIds.add(step.poiId()));

        replacementSearchEngine.findCandidate(phase, patch, intent, usedIds).ifPresent(poi -> {
            int duration = selectedDuration(patch).orElse(preferredDuration(phase, intent));
            PlanStep step = stepFromPoi(poi, phase, duration, intent, "", patch);
            int index = insertionIndex(edited, phase, patch);
            edited.add(index, step);
        });
        return edited;
    }

    private List<PlanStep> tighten(List<PlanStep> steps, PlanPatch patch, PlanIntent intent) {
        List<PlanStep> edited = steps.stream()
                .map(step -> resizeStep(step, Math.max(35, Math.round(step.durationMinutes() * 0.85f))))
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        int currentBusinessMinutes = edited.stream().mapToInt(PlanStep::durationMinutes).sum();
        if (intent.totalMinutes() - currentBusinessMinutes >= 100) return addStep(edited, patch, intent);
        return edited;
    }

    private List<PlanStep> timeShift(List<PlanStep> steps, PlanPatch patch) {
        List<PlanStep> edited = new ArrayList<>(steps);
        if (!patch.requirements().endEarlier()) return edited;

        for (int i = edited.size() - 1; i >= 0; i--) {
            PlanStep step = edited.get(i);
            if (!isKept(step, patch) && !"DINING".equalsIgnoreCase(step.phase())) {
                if (step.durationMinutes() <= 60 && edited.size() > keptCount(edited, patch) + 1) {
                    edited.remove(i);
                } else {
                    edited.set(i, resizeStep(step, Math.max(35, step.durationMinutes() - 30)));
                }
                break;
            }
        }
        return edited;
    }

    private List<PlanStep> reorderSteps(List<PlanStep> steps, PlanPatch patch) {
        List<PlanStep> edited = new ArrayList<>(steps);
        String targetSegmentId = patch.target().segmentId();
        if (targetSegmentId == null || targetSegmentId.isBlank()) return edited;

        int fromIndex = indexBySegmentId(edited, targetSegmentId);
        if (fromIndex < 0) return edited;

        PlanStep moved = edited.remove(fromIndex);
        String position = patch.target().position() == null ? "END" : patch.target().position().toUpperCase(Locale.ROOT);
        String anchorSegmentId = patch.target().anchorSegmentId();

        int insertIndex;
        if ("START".equals(position)) {
            insertIndex = 0;
        } else if ("END".equals(position) || anchorSegmentId == null || anchorSegmentId.isBlank()) {
            insertIndex = edited.size();
        } else {
            int anchorIndex = indexBySegmentId(edited, anchorSegmentId);
            if (anchorIndex < 0) return steps;
            insertIndex = "AFTER".equals(position) ? anchorIndex + 1 : anchorIndex;
        }

        insertIndex = Math.max(0, Math.min(insertIndex, edited.size()));
        edited.add(insertIndex, moved);
        return edited;
    }

    private List<PlanStep> keepAndReplan(List<PlanStep> steps, PlanPatch patch, PlanIntent intent) {
        if (patch.requiresSearch()) return replaceTarget(steps, patch, intent);
        return new ArrayList<>(steps);
    }

    private boolean matchesTarget(PlanStep step, PlanPatch patch) {
        if (patch.target().segmentId() != null &&
                (patch.target().segmentId().equals(step.segmentId()) || patch.target().segmentId().equals(step.poiId()))) return true;
        String targetPhase = firstNonBlank(patch.target().phase(), patch.target().activityType());
        if (targetPhase != null && !normalizePhase(targetPhase).equalsIgnoreCase(step.phase())) return false;
        String range = patch.target().timeRange();
        return range == null || isInTimeRange(step, range);
    }

    private boolean isKept(PlanStep step, PlanPatch patch) {
        for (String keep : patch.requirements().keep()) {
            if (keep.equalsIgnoreCase(step.phase())
                    || keep.equalsIgnoreCase(step.poiId())
                    || keep.equalsIgnoreCase(step.segmentId())
                    || step.poiName().toUpperCase(Locale.ROOT).contains(keep.toUpperCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private boolean isInTimeRange(PlanStep step, String range) {
        int start = step.startTime() == null || step.startTime().isBlank() ? 0 : toMinutes(step.startTime());
        return switch (range) {
            case "MORNING" -> start >= 6 * 60 && start < 11 * 60;
            case "NOON" -> start >= 11 * 60 && start < 14 * 60;
            case "AFTERNOON" -> start >= 12 * 60 && start < 18 * 60;
            case "EVENING" -> start >= 18 * 60 && start < 22 * 60;
            case "NIGHT" -> start >= 22 * 60 || start < 6 * 60;
            default -> true;
        };
    }

    private int findTargetIndex(List<PlanStep> steps, PlanPatch patch) {
        if (patch.target().segmentId() != null) {
            for (int i = 0; i < steps.size(); i++) {
                if (patch.target().segmentId().equals(steps.get(i).segmentId())) return i;
            }
            // Fallback: check if it matches POI ID
            for (int i = 0; i < steps.size(); i++) {
                if (patch.target().segmentId().equals(steps.get(i).poiId())) return i;
            }
        }

        String targetPhase = firstNonBlank(patch.target().phase(), patch.target().activityType());
        boolean replacementLooksLikeActivity = patch.requirements().prefer().contains("CHILD_FRIENDLY")
                || patch.requirements().prefer().contains("INDOOR")
                || patch.requirements().avoid().contains("MALL")
                || targetPhase == null
                || "ACTIVITY".equalsIgnoreCase(targetPhase)
                || "LEISURE".equalsIgnoreCase(targetPhase);
        if ("REPLACE".equals(patch.editType()) && replacementLooksLikeActivity) {
            for (int i = 0; i < steps.size(); i++) {
                String phase = steps.get(i).phase();
                if (!isKept(steps.get(i), patch)
                        && ("ACTIVITY".equalsIgnoreCase(phase) || "LEISURE".equalsIgnoreCase(phase))) {
                    return i;
                }
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            if (matchesTarget(steps.get(i), patch)) return i;
        }

        // 如果用户指定了具体的时段（timeRange，比如晚上），但在该时段未找到能匹配的节点，
        // 则拒绝使用下面的任意第一个非 kept 节点进行暴力替换兜底，直接返回 -1。
        // 这将允许我们在 replaceTarget 中优雅地将替换行为降级为 ADD（新增）操作。
        if (patch.target().timeRange() != null) {
            return -1;
        }

        for (int i = 0; i < steps.size(); i++) {
            if (!isKept(steps.get(i), patch)) return i;
        }
        return -1;
    }

    private int insertionIndex(List<PlanStep> steps, String phase, PlanPatch patch) {
        String timeRange = patch.target().timeRange();
        if (timeRange != null) {
            if ("EVENING".equalsIgnoreCase(timeRange) || "NIGHT".equalsIgnoreCase(timeRange)) {
                return steps.size(); // 晚上或深夜节点插到最末尾
            }
            if ("MORNING".equalsIgnoreCase(timeRange)) {
                return 0; // 上午节点插到最前面
            }
            if ("NOON".equalsIgnoreCase(timeRange)) {
                for (int i = 0; i < steps.size(); i++) {
                    if ("DINING".equalsIgnoreCase(steps.get(i).phase())) return i;
                }
                return Math.max(0, steps.size() - 1);
            }
        }

        if ("DRINKS".equals(phase)) return steps.size();
        if ("DINING".equals(phase)) {
            for (int i = 0; i < steps.size(); i++) {
                if ("DINING".equalsIgnoreCase(steps.get(i).phase())) return i;
            }
        }
        return Math.max(0, steps.size() - 1);
    }

    private int keptCount(List<PlanStep> steps, PlanPatch patch) {
        return (int) steps.stream().filter(step -> isKept(step, patch)).count();
    }

    private int indexBySegmentId(List<PlanStep> steps, String segmentId) {
        for (int i = 0; i < steps.size(); i++) {
            if (segmentId.equals(steps.get(i).segmentId())) return i;
        }
        return -1;
    }

    private PlanIntent applyIntentRequirements(PlanIntent intent, PlanPatch patch) {
        String pace = patch.requirements().pace() == null ? intent.pace() : patch.requirements().pace();
        String budget = patch.requirements().budgetLevel() == null ? intent.budgetLevel() : patch.requirements().budgetLevel();
        String transport = patch.requirements().preferredTransportMode() == null
                ? intent.preferredTransportMode() : patch.requirements().preferredTransportMode();
        List<String> avoid = merge(intent.avoid(), patch.requirements().avoid());
        List<String> mustHave = merge(intent.mustHave(), publicPreferences(patch.requirements().prefer()));
        String endTime = adjustedEndTime(intent, patch);
        int totalMinutes = toMinutes(endTime) - toMinutes(intent.startTime());
        return new PlanIntent(intent.headcount(), intent.participants(), intent.startTime(), endTime,
                totalMinutes, intent.sceneType(), intent.requestedSegments(), intent.dietaryConstraints(),
                intent.drinkPreference(), intent.locationScope(), intent.originalPrompt(), pace, budget,
                intent.hasChildren() || patch.requirements().prefer().contains("CHILD_FRIENDLY"),
                intent.childAge(), transport, avoid, mustHave,
                intent.weatherSensitive() || patch.requirements().prefer().contains("INDOOR"), false);
    }

    private String adjustedEndTime(PlanIntent intent, PlanPatch patch) {
        String originalEnd = intent.endTime();
        if ("TIME_SHIFT".equals(patch.editType()) && patch.requirements().endEarlier()) {
            return originalEnd;
        }
        
        // 如果用户的修改指令明确指明是晚上（EVENING 或 NIGHT），且当前行程终点早于晚上（如 18:00），
        // 允许将终点时间自动顺延至 21:00 以腾出时间空间
        boolean requiresEvening = "EVENING".equalsIgnoreCase(patch.target().timeRange())
                || "NIGHT".equalsIgnoreCase(patch.target().timeRange());
        if (requiresEvening && toMinutes(originalEnd) < toMinutes("21:00")) {
            return "21:00";
        }

        if ("ADD".equals(patch.editType())
                && "EVENING".equalsIgnoreCase(patch.target().timeRange())
                && "DRINKS".equalsIgnoreCase(normalizePhase(firstNonBlank(patch.target().phase(), patch.target().activityType())))
                && toMinutes(originalEnd) < toMinutes("21:00")) {
            return "21:00";
        }
        return originalEnd;
    }

    private List<String> merge(List<String> first, List<String> second) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (first != null) merged.addAll(first);
        if (second != null) merged.addAll(second);
        return List.copyOf(merged);
    }

    private List<String> publicPreferences(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
                .filter(value -> value != null && !value.startsWith("SELECTED_POI:"))
                .toList();
    }

    private PlanStep stripOrderState(PlanStep step) {
        return new PlanStep(step.durationMinutes(), step.startTime(), step.endTime(), step.phase(), step.action(),
                step.poiId(), step.poiName(), step.bookingStatus(), step.note(), step.lnglat(), step.audience(),
                step.reason(), step.budget(), step.headcount(), step.constraints(), "PENDING_CONFIRMATION", "",
                false, "", 0, "", "", step.source(), step.address(), step.telephone(),
                step.businessHours(), step.typeCode(), step.segmentId());
    }

    private Optional<Integer> selectedDuration(PlanPatch patch) {
        return selectedMetadata(patch, "MOVIE_DURATION:")
                .flatMap(value -> {
                    try {
                        return Optional.of(Integer.parseInt(value));
                    } catch (NumberFormatException e) {
                        return Optional.empty();
                    }
                });
    }

    private Optional<String> selectedMetadata(PlanPatch patch, String prefix) {
        if (patch == null || patch.requirements() == null || prefix == null) return Optional.empty();
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private PlanStep stepFromPoi(PoiDto poi, String phase, int duration, PlanIntent intent, String segmentId, PlanPatch patch) {
        String normalizedPhase = normalizePhase(phase);
        Optional<String> movieTitle = selectedMetadata(patch, "MOVIE_TITLE:");
        Optional<String> movieTime = selectedMetadata(patch, "MOVIE_TIME:");
        String action = movieTitle.isPresent() ? "Watch movie" : actionForPhase(normalizedPhase);
        String poiName = movieTitle.orElse(poi.name());
        String reason = movieTitle
                .map(title -> "Selected screening at " + poi.name())
                .orElse(reasonForPoi(poi));
        String note = movieTitle
                .map(title -> "Cinema: " + poi.name()
                        + movieTime.filter(time -> !time.isBlank()).map(time -> ", showtime: " + time).orElse(""))
                .orElse("PlanPatch selected replacement");
        return new PlanStep(duration, "", "", normalizedPhase, action, poi.poiId(), poiName,
                "待确认", note, poi.lnglat(), audience(intent), reason,
                budgetForPoi(poi), safeHeadcount(intent), String.join("、", intent.dietaryConstraints()),
                "PENDING_CONFIRMATION", "", poi.source(), poi.address(), poi.telephone(), poi.businessHours(),
                poi.typeCode(), segmentId);
    }

    private PlanStep resizeStep(PlanStep step, int duration) {
        return new PlanStep(duration, step.startTime(), step.endTime(), step.phase(), step.action(), step.poiId(),
                step.poiName(), step.bookingStatus(), step.note(), step.lnglat(), step.audience(), step.reason(),
                step.budget(), step.headcount(), step.constraints(), step.executionStatus(), step.orderIntentId(),
                step.source(), step.address(), step.telephone(), step.businessHours(), step.typeCode(),
                step.segmentId());
    }

    private String buildSummary(PlanIntent intent, List<PlanStep> timeline, PlanPatch patch) {
        long businessCount = timeline.stream().filter(step -> !step.isTransit() && step.poiId() != null && !step.poiId().isBlank()).count();
        return "已按修改意见更新：" + describePatch(patch) + "。当前行程 "
                + intent.startTime() + "-" + lastEndTime(timeline, intent.endTime()) + "，共 " + businessCount + " 个主要节点。";
    }

    private String buildNotification(PlanIntent intent, List<PlanStep> timeline, PlanPatch patch) {
        StringBuilder sb = new StringBuilder("计划已微调：").append(describePatch(patch));
        for (PlanStep step : timeline) {
            if (!step.isTransit() && step.poiId() != null && !step.poiId().isBlank()) {
                sb.append("；").append(step.startTime()).append(" ").append(step.poiName());
            }
        }
        return sb.toString();
    }

    private List<ReActTrace> buildTrace(PlanPatch patch) {
        return List.of(
                new ReActTrace(1, "INTENT", "PlanPatch " + patch.editType()),
                new ReActTrace(2, "FINISH", describePatch(patch))
        );
    }

    public String describePatch(PlanPatch patch) {
        List<String> parts = new ArrayList<>();
        parts.add(switch (patch.editType()) {
            case "RELAX" -> "放松节奏";
            case "DELETE" -> "删除目标节点";
            case "ADD" -> "增加新节点";
            case "REPLACE" -> "替换目标节点";
            case "TIGHTEN" -> "压缩节奏";
            case "TIME_SHIFT" -> "调整时间";
            default -> "保留重点并局部重排";
        });
        if (!patch.requirements().keep().isEmpty()) parts.add("保留 " + String.join("/", patch.requirements().keep()));
        if (!patch.requirements().prefer().isEmpty()) parts.add("偏好 " + String.join("/", patch.requirements().prefer()));
        if (!patch.requirements().avoid().isEmpty()) parts.add("避开 " + String.join("/", patch.requirements().avoid()));
        if (patch.requirements().endEarlier()) parts.add("尽量早点结束");
        return String.join("，", parts);
    }

    private String lastEndTime(List<PlanStep> timeline, String fallback) {
        if (timeline == null || timeline.isEmpty()) return fallback;
        String end = timeline.get(timeline.size() - 1).endTime();
        return end == null || end.isBlank() ? fallback : end;
    }

    private String inferPhaseFromPatch(PlanPatch patch) {
        if (patch.requirements().prefer().contains("CHILD_FRIENDLY") || patch.requirements().prefer().contains("INDOOR")) return "ACTIVITY";
        return "LEISURE";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return "LEISURE";
        String normalized = phase.toUpperCase(Locale.ROOT);
        if ("RESTAURANT".equals(normalized)) return "DINING";
        if ("BAR".equals(normalized)) return "DRINKS";
        return normalized;
    }

    private int preferredDuration(String phase, PlanIntent intent) {
        int base = switch (phase) {
            case "DINING" -> 60;
            case "DRINKS" -> 75;
            case "LEISURE" -> 60;
            default -> 90;
        };
        if ("RELAXED".equalsIgnoreCase(intent.pace())) return Math.round(base * 1.1f);
        if ("COMPACT".equalsIgnoreCase(intent.pace())) return Math.max(35, Math.round(base * 0.8f));
        return base;
    }

    private String actionForPhase(String phase) {
        return switch (phase) {
            case "DINING" -> "用餐安排";
            case "DRINKS" -> "小酌 / Bar";
            case "LEISURE" -> "轻松活动";
            default -> "活动安排";
        };
    }

    private String reasonForPoi(PoiDto poi) {
        return String.format(Locale.ROOT, "距离约 %.1fkm，标签：%s", poi.distanceKm(), String.join("、", poi.tags()));
    }

    private String budgetForPoi(PoiDto poi) {
        return "RESTAURANT".equals(poi.category()) ? "预计 CNY 60-120/人" : "预计 CNY 60-100/人";
    }

    private String audience(PlanIntent intent) {
        if ("SOLO".equalsIgnoreCase(intent.sceneType())) return "一个人";
        if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) return "朋友小组";
        return "家庭 / 同行人";
    }

    private int safeHeadcount(PlanIntent intent) {
        return intent.headcount() > 0 ? intent.headcount() : 1;
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String formatMinutes(int minutes) {
        int normalized = Math.max(0, minutes);
        return String.format(Locale.ROOT, "%02d:%02d", (normalized / 60) % 24, normalized % 60);
    }
}
