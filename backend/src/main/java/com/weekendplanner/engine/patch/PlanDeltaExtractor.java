package com.weekendplanner.engine.patch;


import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class PlanDeltaExtractor {

    private final PlanPatchExtractor patchExtractor;

    public PlanDeltaExtractor(PlanPatchExtractor patchExtractor) {
        this.patchExtractor = patchExtractor;
    }

    public PlanDelta extract(String feedback, List<PlanStep> timeline, PlanIntent originalIntent) {
        PlanPatch patch = patchExtractor.extract(feedback, timeline, originalIntent);
        return enrichWithConstraintDelta(PlanDelta.fromPatch(patch), feedback);
    }

    private PlanDelta enrichWithConstraintDelta(PlanDelta delta, String feedback) {
        String lower = feedback == null ? "" : feedback.toLowerCase(Locale.ROOT);
        if (lower.contains("4小时") || lower.contains("四小时") || lower.contains("4 小时")) {
            return new PlanDelta(delta.operation(), "PLAN", delta.patch(),
                    new com.weekendplanner.dto.ConstraintSet(null, null, 240, null, List.of(), null,
                            null, null, null, List.of(), List.of(), List.of(),
                            false, null, false, null, null,
                            com.weekendplanner.dto.ExperiencePreference.empty()),
                    delta.lockedSegmentIds(), delta.segmentRequirements(), "PLAN", delta.requiresSearch());
        }
        return delta;
    }
}
