package com.weekendplanner.engine;

import com.weekendplanner.dto.ActionCard;
import com.weekendplanner.dto.Conflict;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.RepairOption;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class RepairOptionGenerator {

    public List<RepairOption> generate(List<Conflict> conflicts, PlanExecutionStore.DraftPlan draft, String prompt) {
        if (conflicts == null || conflicts.isEmpty() || draft == null) return List.of();
        List<RepairOption> options = new ArrayList<>();
        for (Conflict conflict : conflicts) {
            if ("TimeConflict".equalsIgnoreCase(conflict.conflictType())) {
                options.addAll(timeConflictOptions(draft, prompt));
            }
        }
        return options;
    }

    public ActionCard toActionCard(List<Conflict> conflicts, List<RepairOption> repairOptions) {
        List<ActionCard.ActionOption> options = repairOptions.stream()
                .map(option -> new ActionCard.ActionOption(
                        option.optionId(),
                        option.label(),
                        option.description(),
                        option.action(),
                        option.targetSegmentId(),
                        null,
                        option.planDelta() == null ? null : option.planDelta().patch(),
                        option.affectedPoiIds(),
                        option.preview()))
                .toList();
        String reason = conflicts == null || conflicts.isEmpty()
                ? "The requested change needs a decision."
                : conflicts.get(0).reason();
        return new ActionCard(
                "conflict-resolution",
                "行程冲突决策方案",
                reason,
                options,
                "直接说您的偏好，例如：餐厅别换，早点结束",
                true);
    }

    private List<RepairOption> timeConflictOptions(PlanExecutionStore.DraftPlan draft, String prompt) {
        String lowerPrompt = prompt == null ? "" : prompt.toLowerCase();
        boolean isDining = contains(lowerPrompt, "吃", "餐", "饭", "火锅", "烧烤");
        String phase = isDining ? "DINING" : "DRINKS";
        String label = isDining ? "用餐" : "喝酒";

        List<RepairOption> options = new ArrayList<>();
        PlanPatch extendEveningPatch = new PlanPatch(
                "MODIFY_PLAN",
                "ADD",
                new PlanPatch.Target(null, "EVENING", phase, phase, null, null),
                new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"),
                        null, null, null, false),
                true);
        options.add(new RepairOption(
                "extend-evening",
                "顺延到 21:00 并放到晚上",
                "保留白天安排，把新增" + label + "放到晚间时段。",
                "SUBMIT_PATCH",
                null,
                PlanDelta.fromPatch(extendEveningPatch),
                List.of(),
                null));

        List<PlanStep> replaceable = draft.timeline().stream()
                .filter(step -> !step.isTransit())
                .filter(step -> step.segmentId() != null && !step.segmentId().isBlank())
                .filter(step -> !"DINING".equalsIgnoreCase(step.phase()))
                .filter(step -> step.poiId() != null && !step.poiId().isBlank())
                .limit(2)
                .toList();
        for (PlanStep step : replaceable) {
            PlanPatch patch = new PlanPatch(
                    "MODIFY_PLAN",
                    "REPLACE",
                    new PlanPatch.Target(step.segmentId(), null, phase, null, null, null),
                    new PlanPatch.Requirements(List.of("DINING"), List.of(), List.of("NEARBY"),
                            null, null, null, false),
                    true);
            options.add(new RepairOption(
                    "replace-" + step.segmentId(),
                    "把“" + step.poiName() + "”换成" + label,
                    "保持当前结束时间，替换掉这个节点并重新接上路线。",
                    "SUBMIT_PATCH",
                    step.segmentId(),
                    PlanDelta.fromPatch(patch),
                    step.poiId() == null ? List.of() : List.of(step.poiId()),
                    null));
        }
        return options;
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase())) return true;
        }
        return false;
    }
}
