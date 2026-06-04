package com.weekendplanner.engine.context;

import com.weekendplanner.dto.PlanStatus;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.runtime.PlanExecutionStore;

import java.util.List;

public record DraftDigest(
        int version,
        PlanStatus status,
        List<PlanStep> timeline,
        String timelineSummary,
        String notificationText
) {
    public DraftDigest {
        timeline = timeline == null ? List.of() : List.copyOf(timeline);
    }

    public static DraftDigest fromDraft(PlanExecutionStore.DraftPlan draft) {
        if (draft == null) {
            return new DraftDigest(1, PlanStatus.PENDING_CONFIRMATION, List.of(), "", "");
        }
        String summary = draft.timeline().stream()
                .filter(step -> step != null)
                .limit(12)
                .map(step -> (step.isTransit() ? "TRANSIT " : "") + safe(step.startTime()) + " "
                        + safe(step.phase()) + " " + safe(step.poiName()))
                .reduce((left, right) -> left + "; " + right)
                .orElse("");
        return new DraftDigest(draft.version(), draft.status(), draft.timeline(), summary, draft.notificationText());
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
