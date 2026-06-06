package com.weekendplanner.engine.planning;

import com.weekendplanner.dto.Conflict;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.engine.context.PendingAction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class TimelineConstraintValidator {

    public Result validate(List<PlanStep> timeline, PlanIntent intent, PendingAction pending) {
        List<Conflict> conflicts = new ArrayList<>();
        List<PlanStep> business = timeline == null ? List.of() : timeline.stream()
                .filter(step -> step != null && !step.isTransit() && !"TRANSIT".equalsIgnoreCase(step.phase()))
                .toList();

        if (business.isEmpty()) {
            conflicts.add(conflict("EmptyTimeline", "No business timeline was produced."));
            return result(conflicts);
        }

        Optional<String> selectedMovieTime = selectedMovieTime(pending);
        String explicitStart = slot(pending, "startTime").orElse(null);
        String maxEnd = slot(pending, "maxEndTime").orElse(slot(pending, "endTime").orElse(null));
        if (selectedMovieTime.isPresent() && explicitStart != null && maxEnd != null
                && !isWithinWindow(selectedMovieTime.get(), explicitStart, maxEnd)) {
            conflicts.add(conflict("MovieTimeWindowMismatch",
                    "Selected movie time " + selectedMovieTime.get()
                            + " is outside requested window " + explicitStart + "-" + maxEnd));
        } else if (selectedMovieTime.isEmpty() && explicitStart != null && !explicitStart.equals(business.get(0).startTime())) {
            conflicts.add(conflict("StartTimeMismatch",
                    "Expected first business step to start at " + explicitStart + " but got " + business.get(0).startTime()));
        }

        if (maxEnd != null) {
            String actualEnd = lastEnd(timeline);
            if (actualEnd != null && toMinutes(actualEnd) > toMinutes(maxEnd)) {
                conflicts.add(conflict("EndTimeExceeded",
                        "Expected timeline to end by " + maxEnd + " but got " + actualEnd));
            }
        }

        selectedPoiId(pending == null ? null : pending.selectedPatch()).ifPresent(selectedPoi -> {
            boolean present = business.stream().anyMatch(step -> selectedPoi.equals(step.poiId()));
            if (!present) {
                conflicts.add(conflict("LockedCandidateMissing",
                        "Selected candidate " + selectedPoi + " is missing from timeline."));
            }
        });

        String orderPreference = slot(pending, "orderPreference").orElse("");
        if ("ACTIVITY_THEN_DINING".equals(orderPreference)) {
            int activityIndex = firstNonDiningIndex(business);
            int diningIndex = firstIndex(business, "DINING");
            if (activityIndex < 0 || diningIndex < 0 || activityIndex > diningIndex) {
                conflicts.add(conflict("OrderPreferenceViolation",
                        "Expected activity before dining."));
            }
        }

        return result(conflicts);
    }

    private Result result(List<Conflict> conflicts) {
        List<Conflict> finalConflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        String reason = finalConflicts.isEmpty() ? "" : finalConflicts.get(0).reason();
        return new Result(finalConflicts.isEmpty(), finalConflicts, reason);
    }

    private Conflict conflict(String type, String reason) {
        return new Conflict(type, "HIGH", List.of(), reason, List.of());
    }

    private int firstIndex(List<PlanStep> business, String phase) {
        for (int i = 0; i < business.size(); i++) {
            if (phase.equalsIgnoreCase(business.get(i).phase())) return i;
        }
        return -1;
    }

    private int firstNonDiningIndex(List<PlanStep> business) {
        for (int i = 0; i < business.size(); i++) {
            if (!"DINING".equalsIgnoreCase(business.get(i).phase())) return i;
        }
        return -1;
    }

    private String lastEnd(List<PlanStep> timeline) {
        if (timeline == null || timeline.isEmpty()) return null;
        for (int i = timeline.size() - 1; i >= 0; i--) {
            PlanStep step = timeline.get(i);
            if (step != null && step.endTime() != null && !step.endTime().isBlank()) {
                return step.endTime();
            }
        }
        return null;
    }

    private Optional<String> selectedPoiId(PlanPatch patch) {
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return Optional.empty();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith("SELECTED_POI:"))
                .map(value -> value.substring("SELECTED_POI:".length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private Optional<String> selectedMovieTime(PendingAction pending) {
        PlanPatch patch = pending == null ? null : pending.selectedPatch();
        if (patch == null || patch.requirements() == null || patch.requirements().prefer() == null) {
            return Optional.empty();
        }
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith("MOVIE_TIME:"))
                .map(value -> value.substring("MOVIE_TIME:".length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }

    private Optional<String> slot(PendingAction pending, String key) {
        if (pending == null || pending.collectedSlots() == null) return Optional.empty();
        Object value = pending.collectedSlots().get(key);
        if (value == null) return Optional.empty();
        String text = String.valueOf(value).trim();
        return text.isBlank() ? Optional.empty() : Optional.of(text);
    }

    private int toMinutes(String time) {
        if (time == null || !time.contains(":")) return 0;
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    private boolean isWithinWindow(String time, String start, String end) {
        try {
            int value = toMinutes(time);
            return value >= toMinutes(start) && value <= toMinutes(end);
        } catch (Exception e) {
            return false;
        }
    }

    public record Result(boolean valid, List<Conflict> conflicts, String reason) {
        public Result {
            conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
            reason = reason == null ? "" : reason;
        }
    }
}
