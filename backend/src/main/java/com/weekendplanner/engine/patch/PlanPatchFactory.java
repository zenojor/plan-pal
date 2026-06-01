package com.weekendplanner.engine.patch;


import com.weekendplanner.engine.context.AgentContext;
import com.weekendplanner.engine.runtime.AgentCommand;
import com.weekendplanner.engine.runtime.AgentRuntimeProperties;
import com.weekendplanner.engine.runtime.PlanExecutionStore;
import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class PlanPatchFactory {

    private final AgentRuntimeProperties runtime;

    public PlanPatchFactory(AgentRuntimeProperties runtime) {
        this.runtime = runtime == null ? new AgentRuntimeProperties() : runtime;
    }

    public PlanPatch keepAndReplan() {
        return new PlanPatch("MODIFY_PLAN", "KEEP_AND_REPLAN",
                new PlanPatch.Target(null, null, null, null, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), List.of(), null, null, null, false),
                false);
    }

    public PlanDelta editEndTime(String startTime, String newEndTime) {
        int totalMinutes = Math.max(30, toMinutes(newEndTime) - toMinutes(startTime));
        ConstraintSet constraints = new ConstraintSet(null, newEndTime, totalMinutes, null, List.of(),
                null, null, null, null, List.of(), List.of(), List.of(), false, null, false, null, null,
                com.weekendplanner.dto.ExperiencePreference.empty());
        return new PlanDelta("EDIT_TIME", "PLAN", keepAndReplan(), constraints, List.of(), List.of(), "PLAN", false);
    }

    public PlanPatch replacementFor(PlanStep target, List<String> prefer, String budgetLevel) {
        String phase = target == null ? "ACTIVITY" : firstNonBlank(target.phase(), "ACTIVITY");
        String segmentId = target == null ? null : target.segmentId();
        return new PlanPatch("MODIFY_PLAN", "REPLACE",
                new PlanPatch.Target(segmentId, null, phase, phase, null, null),
                new PlanPatch.Requirements(List.of(), List.of(), safeList(prefer), null, budgetLevel, null, false),
                true);
    }

    public PlanPatch addActivity(List<String> prefer, String budgetLevel) {
        return new PlanPatch("MODIFY_PLAN", "ADD",
                new PlanPatch.Target(null, null, "ACTIVITY", "ACTIVITY", null, null),
                new PlanPatch.Requirements(List.of(), List.of(), safeList(prefer), null, budgetLevel, null, false),
                true);
    }

    public PlanPatch replacementFromCommand(AgentContext context, AgentCommand command) {
        String targetSegmentId = firstNonBlank(command.targetSegmentId(), context.segmentId());
        Optional<PlanStep> target = targetById(context.draft(), targetSegmentId);
        if (target.isEmpty()) {
            target = firstReplaceableStep(context.draft());
        }
        List<String> prefer = preferFromSlots(command.slots());
        String budget = budgetFromSlots(command.slots());
        return target.map(step -> replacementFor(step, prefer, budget)).orElseGet(() -> addActivity(prefer, budget));
    }

    public PlanPatch replaceForSegment(PlanExecutionStore.DraftPlan draft, String segmentId) {
        PlanStep target = targetById(draft, segmentId)
                .orElseThrow(() -> new IllegalArgumentException("Replace target not found: " + segmentId));
        return replacementFor(target, List.of(), null);
    }

    public PlanPatch withSegmentId(PlanPatch patch, String segmentId) {
        if (patch == null || segmentId == null || segmentId.isBlank()) return patch;
        if (patch.target().segmentId() != null && !patch.target().segmentId().isBlank()) return patch;
        return new PlanPatch(patch.intent(), patch.editType(),
                new PlanPatch.Target(segmentId, patch.target().timeRange(), patch.target().activityType(),
                        patch.target().phase(), patch.target().anchorSegmentId(), patch.target().position()),
                patch.requirements(), patch.requiresSearch());
    }

    public PlanPatch withSelectedPoi(PlanPatch patch, String segmentId, String poiId, String phase) {
        List<String> prefer = withoutSelectedPoi(patch.requirements().prefer());
        prefer.add(runtime.getSelectedPoiPrefix() + poiId);
        return new PlanPatch(patch.intent(), "REPLACE",
                new PlanPatch.Target(segmentId, patch.target().timeRange(), phase, phase,
                        patch.target().anchorSegmentId(), patch.target().position()),
                new PlanPatch.Requirements(patch.requirements().keep(), patch.requirements().avoid(), prefer,
                        patch.requirements().pace(), patch.requirements().budgetLevel(),
                        patch.requirements().preferredTransportMode(), patch.requirements().endEarlier()),
                true);
    }

    public PlanPatch withSelectedPoiForAdd(PlanPatch patch, String poiId, String phase) {
        List<String> prefer = withoutSelectedPoi(patch.requirements().prefer());
        prefer.add(runtime.getSelectedPoiPrefix() + poiId);
        return new PlanPatch(patch.intent(), "ADD",
                new PlanPatch.Target(patch.target().segmentId(), patch.target().timeRange(), phase, phase,
                        patch.target().anchorSegmentId(), patch.target().position()),
                new PlanPatch.Requirements(patch.requirements().keep(), patch.requirements().avoid(), prefer,
                        patch.requirements().pace(), patch.requirements().budgetLevel(),
                        patch.requirements().preferredTransportMode(), patch.requirements().endEarlier()),
                true);
    }

    public Optional<String> selectedPoiId(PlanPatch patch) {
        if (patch == null || patch.requirements() == null) return Optional.empty();
        String prefix = runtime.getSelectedPoiPrefix();
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith(prefix))
                .map(value -> value.substring(prefix.length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private Optional<PlanStep> targetById(PlanExecutionStore.DraftPlan draft, String segmentId) {
        if (draft == null || segmentId == null || segmentId.isBlank()) return Optional.empty();
        return draft.timeline().stream()
                .filter(step -> segmentId.equals(step.segmentId()) || segmentId.equals(step.poiId()))
                .findFirst();
    }

    private Optional<PlanStep> firstReplaceableStep(PlanExecutionStore.DraftPlan draft) {
        if (draft == null) return Optional.empty();
        Optional<PlanStep> nonDining = draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .filter(step -> !"DINING".equalsIgnoreCase(step.phase()))
                .findFirst();
        if (nonDining.isPresent()) return nonDining;
        return draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .findFirst();
    }

    private List<String> preferFromSlots(Map<String, Object> slots) {
        if (slots != null && "nearer".equals(slots.get("distancePreference"))) {
            return List.of("NEARBY");
        }
        return List.of();
    }

    private String budgetFromSlots(Map<String, Object> slots) {
        return slots == null ? null : (String) slots.get("budgetLevel");
    }

    private List<String> withoutSelectedPoi(List<String> prefer) {
        List<String> values = new ArrayList<>(safeList(prefer));
        values.removeIf(value -> value != null && value.startsWith(runtime.getSelectedPoiPrefix()));
        return values;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : List.copyOf(values);
    }

    private int toMinutes(String time) {
        if (time == null || time.isBlank()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }
}
