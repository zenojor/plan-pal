package com.weekendplanner.engine.patch;


import com.weekendplanner.engine.planning.ReplacementSearchEngine;
import com.weekendplanner.engine.planning.TimelineConstraintValidator;
import com.weekendplanner.engine.planning.TimelineAssembler;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.engine.context.PendingAction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.Conflict;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanResponse;
import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.dto.RepairOption;
import com.weekendplanner.dto.WorkflowTrace;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
public class PlanEditorEngine {

    private static final int DEFAULT_MOVIE_OPTIONAL_BUFFER_MINUTES = 60;

    private final PlanExecutionStore executionStore;
    private final TimelineAssembler timelineAssembler;
    private final ReplacementSearchEngine replacementSearchEngine;

    public PlanEditorEngine(PlanExecutionStore executionStore,
                            TimelineAssembler timelineAssembler,
                            ReplacementSearchEngine replacementSearchEngine) {
        this.executionStore = executionStore;
        this.timelineAssembler = timelineAssembler;
        this.replacementSearchEngine = replacementSearchEngine;
    }

    public PlanResponse applyPatch(PlanExecutionStore.DraftPlan draft, PlanPatch patch) {
        return applyDelta(draft, PlanDelta.fromPatch(patch));
    }

    public PlanResponse applyPendingSelectedPatch(PlanExecutionStore.DraftPlan draft, PendingAction pending) {
        if (draft == null || pending == null || pending.selectedPatch() == null) {
            return conflictResponse(draft, draft == null ? null : draft.intent(), List.of(),
                    "Missing selected candidate for pending workflow");
        }
        PlanPatch patch = movieCompatiblePatch(pending.selectedPatch(), pending);
        PendingAction effectivePending = pending.withSelectedPatch(patch, pending.selectedLabel(),
                pending.workflowType(), pending.requiredSlots());
        PlanIntent intent = intentFromPending(draft.intent(), effectivePending, true);
        String phase = normalizePhase(firstNonBlank(patch.target().phase(), patch.target().activityType(), "ACTIVITY"));
        Optional<PoiDto> poiOpt = replacementSearchEngine == null
                ? Optional.empty()
                : replacementSearchEngine.findCandidate(phase, patch, intent, Set.of());
        if (poiOpt.isEmpty()) {
            return conflictResponse(draft, intent, List.of(), "Selected movie/candidate is no longer available");
        }
        int duration = selectedDuration(patch).orElse(preferredDuration(phase, intent));
        PlanStep selected = stepFromPoi(poiOpt.get(), phase, duration, intent, "", patch);
        TimelineAssembler.Result rebuilt = timelineAssembler.assemble(draft.planId(), intent, List.of(selected), true, 0);
        TimelineConstraintValidator.Result validation = new TimelineConstraintValidator()
                .validate(rebuilt.timeline(), intent, effectivePending);
        if (!validation.valid()) {
            return conflictResponse(draft, intent, validation.conflicts(), validation.reason());
        }
        return savePendingPlan(draft, intent, rebuilt, patch, "已按你选的候选生成行程。");
    }

    public PlanResponse applyLockedCandidatePlan(PlanExecutionStore.DraftPlan draft, PendingAction pending) {
        if (draft == null || pending == null || pending.selectedPatch() == null) {
            return conflictResponse(draft, draft == null ? null : draft.intent(), List.of(),
                    "Missing selected candidate for locked planning workflow");
        }
        PlanPatch selectedPatch = pending.selectedPatch();
        PlanIntent intent = intentFromPending(draft.intent(), pending, false);
        String selectedPhase = normalizePhase(firstNonBlank(selectedPatch.target().phase(), selectedPatch.target().activityType(), "DINING"));

        List<String> diningPrefer = new ArrayList<>();
        List<String> activityPrefer = new ArrayList<>();
        if (selectedPatch.requirements() != null && selectedPatch.requirements().prefer() != null) {
            for (String pref : selectedPatch.requirements().prefer()) {
                if (pref == null) continue;
                if (pref.startsWith("SELECTED_POI:")) {
                    String poiId = pref.substring("SELECTED_POI:".length()).trim();
                    if (replacementSearchEngine.isRestaurant(poiId)) {
                        diningPrefer.add(pref);
                    } else {
                        activityPrefer.add(pref);
                    }
                } else if (pref.startsWith("MOVIE_")) {
                    activityPrefer.add(pref);
                } else {
                    diningPrefer.add(pref);
                    activityPrefer.add(pref);
                }
            }
        }
        if (!activityPrefer.contains("NEARBY")) activityPrefer.add("NEARBY");
        if (!activityPrefer.contains("INDOOR")) activityPrefer.add("INDOOR");

        PlanPatch.Requirements diningReqs = new PlanPatch.Requirements(
                selectedPatch.requirements().keep(),
                selectedPatch.requirements().avoid(),
                diningPrefer,
                selectedPatch.requirements().pace(),
                selectedPatch.requirements().budgetLevel(),
                selectedPatch.requirements().preferredTransportMode(),
                selectedPatch.requirements().endEarlier()
        );
        PlanPatch diningPatch = new PlanPatch(selectedPatch.intent(), selectedPatch.editType(),
                new PlanPatch.Target(selectedPatch.target().segmentId(), selectedPatch.target().timeRange(),
                        "DINING", "DINING", selectedPatch.target().position(), selectedPatch.target().anchorSegmentId()),
                diningReqs, selectedPatch.requiresSearch());

        Optional<PoiDto> selectedPoiOpt = replacementSearchEngine == null
                ? Optional.empty()
                : replacementSearchEngine.findCandidate(selectedPhase, diningPatch, intent, Set.of());
        if (selectedPoiOpt.isEmpty()) {
            return conflictResponse(draft, intent, List.of(), "Selected dining candidate is no longer available");
        }

        int maxDuration = slotInt(pending, "maxDurationMinutes")
                .orElse(slotInt(pending, "durationMinutes").orElse(Math.max(180, intent.totalMinutes())));
        int diningDuration = Math.min(90, Math.max(60, preferredDuration(selectedPhase, intent)));
        PlanStep dining = stepFromPoi(selectedPoiOpt.get(), selectedPhase, diningDuration, intent, "", diningPatch);

        String otherPhase = "ACTIVITY";
        if (selectedPatch.requirements().prefer().stream().anyMatch(pref ->
                pref != null && (pref.startsWith("MOVIE_") || pref.startsWith("SCREENING_ID:")))) {
            otherPhase = "CINEMA";
        } else if (intent.requestedSegments().contains("DRINKS")) {
            otherPhase = "DRINKS";
        } else if (intent.requestedSegments().contains("LEISURE")) {
            otherPhase = "LEISURE";
        }

        PlanPatch activityPatch = new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, otherPhase, otherPhase, null, null),
                new PlanPatch.Requirements(List.of(), selectedPatch.requirements().avoid(),
                        activityPrefer, "RELAXED", null, null, false),
                true);
        Optional<PoiDto> activityPoiOpt = replacementSearchEngine.findCandidate(otherPhase, activityPatch, intent,
                Set.of(dining.poiId()));
        if (activityPoiOpt.isEmpty()) {
            return conflictResponse(draft, intent, List.of(), "No light activity candidate is available for " + otherPhase.toLowerCase(Locale.ROOT) + " before dining");
        }
        int activityDuration = selectedDuration(activityPatch)
                .orElse(Math.max(60, Math.min(120, maxDuration - diningDuration - 30)));
        PlanStep activity = stepFromPoi(activityPoiOpt.get(), otherPhase, activityDuration, intent, "", activityPatch);

        List<PlanStep> businessSteps = "DINING_THEN_ACTIVITY".equals(String.valueOf(pending.collectedSlots().get("orderPreference")))
                || "DINING_THEN_DRINKS".equals(String.valueOf(pending.collectedSlots().get("orderPreference")))
                ? List.of(dining, activity)
                : List.of(activity, dining);
        TimelineAssembler.Result rebuilt = timelineAssembler.assemble(draft.planId(), intent, businessSteps, true, 0);
        TimelineConstraintValidator.Result validation = new TimelineConstraintValidator()
                .validate(rebuilt.timeline(), intent, pending);
        if (!validation.valid()) {
            return conflictResponse(draft, intent, validation.conflicts(), validation.reason());
        }
        return savePendingPlan(draft, intent, rebuilt, selectedPatch, "已按你补充的时间和顺序生成行程。");
    }

    public PlanResponse applySelectedCandidateChain(PlanExecutionStore.DraftPlan draft, PendingAction pending) {
        if (draft == null || pending == null || pending.selectedPatch() == null) {
            return conflictResponse(draft, draft == null ? null : draft.intent(), List.of(),
                    "Missing selected dining candidate for chained planning workflow");
        }
        Optional<PlanPatch> drinksPatchOpt = patchSlot(pending, "selectedDrinksPatch");
        if (drinksPatchOpt.isEmpty()) {
            return conflictResponse(draft, draft.intent(), List.of(),
                    "Missing selected drinks candidate for chained planning workflow");
        }
        PlanPatch diningPatch = pending.selectedPatch();
        PlanPatch drinksPatch = drinksPatchOpt.get();
        PlanIntent intent = intentFromPending(draft.intent(), pending, false);

        Optional<PoiDto> diningPoiOpt = replacementSearchEngine == null
                ? Optional.empty()
                : replacementSearchEngine.findCandidate("DINING", diningPatch, intent, Set.of());
        if (diningPoiOpt.isEmpty()) {
            return conflictResponse(draft, intent, List.of(), "Selected dining candidate is no longer available");
        }
        Optional<PoiDto> drinksPoiOpt = replacementSearchEngine.findCandidate("DRINKS", drinksPatch, intent,
                Set.of(diningPoiOpt.get().poiId()));
        if (drinksPoiOpt.isEmpty()) {
            return conflictResponse(draft, intent, List.of(), "Selected drinks candidate is no longer available");
        }

        int maxDuration = slotInt(pending, "maxDurationMinutes")
                .orElse(slotInt(pending, "durationMinutes").orElse(Math.max(180, intent.totalMinutes())));
        int diningDuration = selectedDuration(diningPatch)
                .orElse(Math.min(90, Math.max(60, preferredDuration("DINING", intent))));
        int drinksDuration = selectedDuration(drinksPatch)
                .orElse(Math.min(100, Math.max(60, Math.min(preferredDuration("DRINKS", intent),
                        maxDuration - diningDuration - 20))));
        PlanStep dining = stepFromPoi(diningPoiOpt.get(), "DINING", diningDuration, intent, "", diningPatch);
        PlanStep drinks = stepFromPoi(drinksPoiOpt.get(), "DRINKS", drinksDuration, intent, "", drinksPatch);
        TimelineAssembler.Result rebuilt = timelineAssembler.assemble(draft.planId(), intent,
                List.of(dining, drinks), true, 0);
        TimelineConstraintValidator.Result validation = new TimelineConstraintValidator()
                .validate(rebuilt.timeline(), intent, pending);
        if (!validation.valid()) {
            return conflictResponse(draft, intent, validation.conflicts(), validation.reason());
        }
        return savePendingPlan(draft, intent, rebuilt, drinksPatch, "已按你选的餐厅和清吧生成行程。");
    }

    public PlanResponse applyDelta(PlanExecutionStore.DraftPlan draft, PlanDelta delta) {
        PlanPatch patch = delta == null || delta.patch() == null
                ? new PlanPatch("MODIFY_PLAN", "KEEP_AND_REPLAN", null, null, false)
                : delta.patch();
        PlanIntent updatedIntent = applyIntentRequirements(draft.intent(), patch);
        if (delta != null && delta.changedConstraints() != null && delta.changedConstraints().totalMinutes() != null) {
            updatedIntent = withTotalMinutes(updatedIntent, delta.changedConstraints().totalMinutes());
        }
        boolean replacingBuffer = targetsBufferStep(draft.timeline(), patch);
        List<PlanStep> businessSteps = draft.timeline().stream()
                .filter(step -> step != null && !step.isTransit() && !"TRANSIT".equalsIgnoreCase(step.phase()))
                .filter(step -> !isBufferStep(step) || replacingBuffer && matchesTarget(step, patch))
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

    private boolean isBufferStep(PlanStep step) {
        return step != null && ("BUFFER".equalsIgnoreCase(step.executionStatus())
                || ("LEISURE".equalsIgnoreCase(step.phase())
                && (step.poiId() == null || step.poiId().isBlank())
                && "system".equalsIgnoreCase(step.source())));
    }

    private boolean targetsBufferStep(List<PlanStep> timeline, PlanPatch patch) {
        if (timeline == null || patch == null || patch.target() == null
                || !"REPLACE".equalsIgnoreCase(patch.editType())
                || patch.target().segmentId() == null || patch.target().segmentId().isBlank()) {
            return false;
        }
        return timeline.stream()
                .filter(this::isBufferStep)
                .anyMatch(step -> patch.target().segmentId().equals(step.segmentId()));
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

    private PlanPatch movieCompatiblePatch(PlanPatch patch, PendingAction pending) {
        Optional<String> compatibleTime = compatibleMovieTime(patch, pending);
        if (compatibleTime.isEmpty()) return patch;
        Optional<String> currentTime = selectedMetadata(patch, "MOVIE_TIME:");
        if (currentTime.isPresent() && currentTime.get().equals(compatibleTime.get())) return patch;
        List<String> prefer = new ArrayList<>(patch.requirements().prefer());
        boolean replaced = false;
        for (int i = 0; i < prefer.size(); i++) {
            String value = prefer.get(i);
            if (value != null && value.startsWith("MOVIE_TIME:")) {
                prefer.set(i, "MOVIE_TIME:" + compatibleTime.get());
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            prefer.add("MOVIE_TIME:" + compatibleTime.get());
        }
        PlanPatch.Requirements requirements = patch.requirements();
        return new PlanPatch(patch.intent(), patch.editType(), patch.target(),
                new PlanPatch.Requirements(requirements.keep(), requirements.avoid(), prefer,
                        requirements.pace(), requirements.budgetLevel(), requirements.preferredTransportMode(),
                        requirements.endEarlier()),
                patch.requiresSearch());
    }

    private Optional<String> compatibleMovieTime(PlanPatch patch, PendingAction pending) {
        if (patch == null || patch.requirements() == null) return Optional.empty();
        Optional<String> selectedTime = selectedMetadata(patch, "MOVIE_TIME:");
        Optional<String> windowStart = slotString(pending, "startTime");
        Optional<String> windowEnd = slotString(pending, "maxEndTime").or(() -> slotString(pending, "endTime"));
        if (windowStart.isEmpty() || windowEnd.isEmpty()) return selectedTime;
        if (selectedTime.filter(time -> isWithinWindow(time, windowStart.get(), windowEnd.get())).isPresent()) {
            return selectedTime;
        }
        return selectedMetadata(patch, "MOVIE_SHOWTIMES:")
                .stream()
                .flatMap(value -> List.of(value.split("\\|")).stream())
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .filter(time -> isWithinWindow(time, windowStart.get(), windowEnd.get()))
                .findFirst();
    }

    private boolean isWithinWindow(String time, String start, String end) {
        try {
            int value = toMinutes(time);
            return value >= toMinutes(start) && value <= toMinutes(end);
        } catch (Exception e) {
            return false;
        }
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

    private PlanIntent intentFromPending(PlanIntent baseIntent, PendingAction pending, boolean movieWorkflow) {
        PlanIntent base = baseIntent == null
                ? new PlanIntent(1, List.of(), "14:00", "18:00", 240, "SOCIAL",
                List.of("ACTIVITY", "DINING"), List.of(), null, "NEARBY", "", false)
                : baseIntent;
        PlanPatch selectedPatch = pending.selectedPatch();
        String movieTime = movieWorkflow ? compatibleMovieTime(selectedPatch, pending).orElse(null) : null;
        String start = firstNonBlank(movieTime, slotString(pending, "startTime").orElse(null), base.startTime(), "14:00");
        Optional<Integer> explicitMaxDuration = slotInt(pending, "maxDurationMinutes");
        Optional<Integer> explicitDuration = slotInt(pending, "durationMinutes");
        Optional<String> explicitMaxEnd = slotString(pending, "maxEndTime");
        Optional<String> explicitEnd = slotString(pending, "endTime");
        int movieDuration = selectedDuration(selectedPatch).orElse(120);
        int totalMinutes = explicitMaxDuration
                .orElse(explicitDuration
                        .orElse(movieWorkflow
                                ? movieDuration + DEFAULT_MOVIE_OPTIONAL_BUFFER_MINUTES
                                : Math.max(180, base.totalMinutes())));
        String end = firstNonBlank(explicitMaxEnd.orElse(null), explicitEnd.orElse(null), addMinutes(start, totalMinutes));
        totalMinutes = Math.max(30, toMinutes(end) - toMinutes(start));
        int headcount = slotInt(pending, "headcount").orElse(base.headcount() > 0 ? base.headcount() : 1);
        String locationScope = firstNonBlank(slotString(pending, "locationScope").orElse(null),
                base.locationScope(), "NEARBY");
        String pace = firstNonBlank(slotString(pending, "pace").orElse(null), base.pace(), "RELAXED");
        String sceneType = firstNonBlank(base.sceneType(), headcount > 1 ? "SOCIAL" : "SOLO");
        List<String> segments = movieWorkflow ? List.of("ACTIVITY")
                : (base.requestedSegments().isEmpty() ? List.of("ACTIVITY", "DINING") : base.requestedSegments());
        return new PlanIntent(headcount, base.participants(), start, end, totalMinutes, sceneType, segments,
                base.dietaryConstraints(), base.drinkPreference(), locationScope, base.originalPrompt(), pace,
                base.budgetLevel(), base.hasChildren(), base.childAge(), base.preferredTransportMode(),
                base.avoid(), base.mustHave(), base.weatherSensitive(), false);
    }

    private PlanResponse savePendingPlan(PlanExecutionStore.DraftPlan draft,
                                         PlanIntent intent,
                                         TimelineAssembler.Result rebuilt,
                                         PlanPatch patch,
                                         String summary) {
        String notificationText = buildNotification(intent, rebuilt.timeline(), patch);
        PlanExecutionStore.DraftPlan saved = draft.nextVersion(intent, rebuilt.timeline(),
                rebuilt.orderIntents(), notificationText);
        executionStore.save(saved);
        return new PlanResponse(draft.planId(), draft.userId(), "SUCCESS", summary,
                rebuilt.timeline(), buildTrace(patch), "", notificationText, null,
                intent, rebuilt.orderIntents(), "PENDING_CONFIRMATION", saved.version(),
                PlanStatus.MODIFIED, List.of(), List.of(), null);
    }

    private PlanResponse conflictResponse(PlanExecutionStore.DraftPlan draft,
                                          PlanIntent intent,
                                          List<Conflict> conflicts,
                                          String reason) {
        String planId = draft == null ? "" : draft.planId();
        String userId = draft == null ? "" : draft.userId();
        List<PlanStep> timeline = draft == null ? List.of() : draft.timeline();
        List<Conflict> finalConflicts = conflicts == null || conflicts.isEmpty()
                ? List.of(new Conflict("PendingWorkflowConflict", "HIGH", List.of(),
                reason == null ? "Pending workflow cannot be completed safely" : reason, List.of()))
                : conflicts;
        return new PlanResponse(planId, userId, "CONFLICT",
                reason == null ? "需要先解决一个行程约束冲突。" : reason,
                timeline, List.of(), "", draft == null ? "" : draft.notificationText(), reason,
                intent, draft == null ? List.of() : draft.orderIntents(), "PENDING_CONFIRMATION",
                draft == null ? 1 : draft.version(), draft == null ? PlanStatus.PENDING_CONFIRMATION : draft.status(),
                finalConflicts, List.<RepairOption>of(), null);
    }

    private Optional<String> slotString(PendingAction pending, String key) {
        if (pending == null || pending.collectedSlots() == null) return Optional.empty();
        Object value = pending.collectedSlots().get(key);
        if (value == null) return Optional.empty();
        String text = String.valueOf(value).trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private Optional<PlanPatch> patchSlot(PendingAction pending, String key) {
        if (pending == null || pending.collectedSlots() == null) return Optional.empty();
        Object value = pending.collectedSlots().get(key);
        if (value instanceof PlanPatch patch) return Optional.of(patch);
        return Optional.empty();
    }

    private Optional<Integer> slotInt(PendingAction pending, String key) {
        if (pending == null || pending.collectedSlots() == null) return Optional.empty();
        Object value = pending.collectedSlots().get(key);
        if (value instanceof Number number) return Optional.of(number.intValue());
        if (value == null) return Optional.empty();
        try {
            return Optional.of(Integer.parseInt(String.valueOf(value).trim()));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
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

    private List<WorkflowTrace> buildTrace(PlanPatch patch) {
        return List.of(
                new WorkflowTrace(1, "INTENT", "PlanPatch " + patch.editType()),
                new WorkflowTrace(2, "FINISH", describePatch(patch))
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
        int headcount = safeHeadcount(intent);
        if ("SOLO".equalsIgnoreCase(intent.sceneType()) || headcount == 1) return "一个人";
        if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
            return intent.hasChildren() ? "亲子 / 家庭" : "家人同行";
        }
        if ("DATE".equalsIgnoreCase(intent.sceneType())) return headcount + " 人约会";
        return headcount + " 人同行";
    }

    private int safeHeadcount(PlanIntent intent) {
        return intent.headcount() > 0 ? intent.headcount() : 1;
    }

    private int toMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private String addMinutes(String time, int minutes) {
        return formatMinutes(toMinutes(time) + minutes);
    }

    private String formatMinutes(int minutes) {
        int normalized = Math.max(0, minutes);
        return String.format(Locale.ROOT, "%02d:%02d", (normalized / 60) % 24, normalized % 60);
    }
}
