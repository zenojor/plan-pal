package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.ExperiencePreference;
import com.weekendplanner.dto.PlanIntent;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Component
public class PlanningAssumptionService {

    public AssumptionResult apply(PlanIntent intent, ConstraintSet constraints) {
        ConstraintSet base = constraints == null ? ConstraintSet.fromIntent(intent) : constraints;
        ExperiencePreference preference = base.experiencePreference();
        List<String> notes = new ArrayList<>();
        String sceneType = firstNonBlank(base.sceneType(), intent == null ? null : intent.sceneType());
        String start = firstNonBlank(base.startTime(), intent == null ? null : intent.startTime());
        String end = firstNonBlank(base.endTime(), intent == null ? null : intent.endTime());

        if (blank(start) || blank(end)) {
            TimeWindow window = windowFor(preference == null ? null : preference.timeHint());
            if (window != null) {
                start = firstNonBlank(start, window.startTime());
                end = firstNonBlank(end, window.endTime());
                notes.add("assumed_time:" + window.label());
            }
        }

        Integer headcount = base.headcount();
        if (headcount == null || headcount <= 0
                || (headcount == 1 && ("DATE".equalsIgnoreCase(sceneType) || looksLikeDatePreference(preference)))) {
            Integer inferred = inferHeadcount(sceneType, intent, preference);
            if (inferred != null && inferred > 0) {
                headcount = inferred;
                notes.add("assumed_headcount:" + inferred);
            }
        }

        String locationScope = firstNonBlank(base.locationScope(), intent == null ? null : intent.locationScope());
        if (blank(locationScope) && preference != null && "nearby".equalsIgnoreCase(preference.locationHint())) {
            locationScope = "nearby";
            notes.add("assumed_location:nearby");
        }

        int totalMinutes = toMinutes(end) - toMinutes(start);
        if (totalMinutes <= 0) totalMinutes = base.totalMinutes() == null ? 0 : base.totalMinutes();
        ConstraintSet nextConstraints = base.withPlanningContext(start, end, totalMinutes, headcount, locationScope, preference);
        PlanIntent nextIntent = intent == null ? null : new PlanIntent(
                headcount == null || headcount <= 0 ? intent.headcount() : headcount,
                intent.participants(),
                start,
                end,
                Math.max(totalMinutes, 0),
                intent.sceneType(),
                intent.requestedSegments(),
                intent.dietaryConstraints(),
                intent.drinkPreference(),
                locationScope,
                intent.originalPrompt(),
                intent.pace(),
                intent.budgetLevel(),
                intent.hasChildren(),
                intent.childAge(),
                intent.preferredTransportMode(),
                intent.avoid(),
                intent.mustHave(),
                intent.weatherSensitive(),
                intent.isConsultingMode());
        return new AssumptionResult(nextIntent, nextConstraints, List.copyOf(notes),
                hasConcretePlanningWindow(nextIntent));
    }

    private Integer inferHeadcount(String sceneType, PlanIntent intent, ExperiencePreference preference) {
        String scene = sceneType == null ? "" : sceneType.toUpperCase(Locale.ROOT);
        if ("DATE".equals(scene) || looksLikeDatePreference(preference)) return 2;
        if ("SOLO".equals(scene)) return 1;
        if ("FAMILY".equals(scene)) return intent != null && intent.hasChildren() ? 3 : null;
        return intent != null && intent.headcount() > 0 ? intent.headcount() : null;
    }

    private boolean looksLikeDatePreference(ExperiencePreference preference) {
        return preference != null
                && (preference.avoid().contains("awkward_silence")
                || preference.moods().contains("ritual")
                || preference.activityBiases().contains("dessert"));
    }

    private TimeWindow windowFor(String timeHint) {
        String hint = timeHint == null ? "" : timeHint.toLowerCase(Locale.ROOT);
        return switch (hint) {
            case "morning" -> new TimeWindow("10:00", "12:30", "morning");
            case "noon" -> new TimeWindow("12:00", "14:00", "noon");
            case "afternoon" -> new TimeWindow("14:00", "17:00", "afternoon");
            case "evening" -> new TimeWindow("19:00", "22:00", "evening");
            default -> null;
        };
    }

    private boolean hasConcretePlanningWindow(PlanIntent intent) {
        return intent != null
                && !blank(intent.startTime())
                && !blank(intent.endTime())
                && intent.totalMinutes() > 0
                && intent.headcount() > 0;
    }

    private int toMinutes(String time) {
        if (blank(time) || !time.contains(":")) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        try {
            return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private String firstNonBlank(String first, String fallback) {
        return blank(first) ? fallback : first;
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record AssumptionResult(
            PlanIntent intent,
            ConstraintSet constraints,
            List<String> notes,
            boolean executable
    ) {
        public AssumptionResult {
            notes = notes == null ? List.of() : List.copyOf(notes);
        }
    }

    private record TimeWindow(String startTime, String endTime, String label) {
    }
}
