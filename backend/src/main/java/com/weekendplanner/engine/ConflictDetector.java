package com.weekendplanner.engine;

import com.weekendplanner.dto.Conflict;
import com.weekendplanner.dto.PlanDelta;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class ConflictDetector {

    public List<Conflict> detect(PlanExecutionStore.DraftPlan draft, String prompt, PlanDelta delta) {
        if (draft == null) return List.of();
        if (shouldOfferEveningConflict(draft, prompt, delta == null ? null : delta.patch())) {
            List<String> affectedSegments = draft.timeline().stream()
                    .filter(step -> step != null && !step.isTransit())
                    .map(PlanStep::segmentId)
                    .filter(segmentId -> segmentId != null && !segmentId.isBlank())
                    .toList();
            return List.of(new Conflict(
                    "TimeConflict",
                    "MEDIUM",
                    affectedSegments,
                    "The current plan ends before the requested evening dining/drinks/activity window.",
                    List.of()));
        }
        return List.of();
    }

    private boolean shouldOfferEveningConflict(PlanExecutionStore.DraftPlan draft, String prompt, PlanPatch directPatch) {
        if (directPatch != null) return false;
        String lowerPrompt = prompt == null ? "" : prompt.toLowerCase(Locale.ROOT);

        boolean wantsEvening = contains(lowerPrompt, "晚上", "晚间", "夜间", "今晚", "evening", "night");
        boolean wantsDiningOrDrinksOrActivity = contains(lowerPrompt,
                "吃", "饭", "餐", "火锅", "烧烤", "喝", "酒吧", "bar", "玩", "看", "活动");
        boolean isOriginalAfternoonOnly = draft.intent().endTime() != null
                && draft.intent().endTime().compareTo("18:00") <= 0;
        boolean isResolution = contains(lowerPrompt, "顺延", "去掉", "替换", "换成");

        return wantsEvening && wantsDiningOrDrinksOrActivity && isOriginalAfternoonOnly && !isResolution;
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
