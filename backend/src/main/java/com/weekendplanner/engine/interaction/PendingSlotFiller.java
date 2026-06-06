package com.weekendplanner.engine.interaction;

import com.weekendplanner.engine.context.PendingAction;
import com.weekendplanner.engine.context.SessionState;
import com.weekendplanner.engine.understanding.SlotName;
import com.weekendplanner.engine.understanding.TurnIntent;
import com.weekendplanner.engine.understanding.TurnUnderstanding;
import com.weekendplanner.engine.understanding.TurnUnderstandingService;
import com.weekendplanner.engine.understanding.UnderstandingRequest;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@Component
public class PendingSlotFiller {

    private final TurnUnderstandingService understandingService;

    public PendingSlotFiller() {
        this(TurnUnderstandingService.fallbackOnly());
    }

    public PendingSlotFiller(TurnUnderstandingService understandingService) {
        this.understandingService = understandingService == null
                ? TurnUnderstandingService.fallbackOnly()
                : understandingService;
    }

    public PendingSlotPatch extract(PendingAction pending, String input, SessionState state) {
        if (pending == null || input == null || input.isBlank()) return PendingSlotPatch.empty();
        TurnUnderstanding understanding = understandingService.understand(
                new UnderstandingRequest(input, pending,
                        state == null ? java.util.List.of() : state.lastCandidates(),
                        state == null ? java.util.List.of() : state.currentPlan(),
                        state == null ? java.util.List.of() : state.recentEvents(),
                        "pending-slot-fill"));
        return extract(pending, understanding);
    }

    public PendingSlotPatch extract(PendingAction pending, TurnUnderstanding understanding) {
        if (pending == null || understanding == null) return PendingSlotPatch.empty();
        boolean question = understanding.readOnlyQuestion() || understanding.turnIntent() == TurnIntent.READ_ONLY_QUESTION;
        boolean correction = "fallback.movie.correction".equals(understanding.reasonCode())
                || "pending.movie.correction".equals(understanding.reasonCode());
        Map<String, Object> slots = question ? Map.of()
                : filterAllowedSlots(pending, understandingService.toPendingSlots(understanding));
        String reason = correction ? "pending.movie.correction"
                : slots.isEmpty() ? understanding.reasonCode() : "pending.slot.fill:" + String.join(",", slots.keySet());
        return new PendingSlotPatch(slots, question, correction, reason, understanding);
    }

    public boolean looksLikeQuestion(String text) {
        return understandingService.looksLikeQuestion(text);
    }

    private Map<String, Object> filterAllowedSlots(PendingAction pending, Map<String, Object> slots) {
        if (slots == null || slots.isEmpty()) return Map.of();
        Set<String> allowed = allowedKeys(pending);
        return slots.entrySet().stream()
                .filter(entry -> allowed.contains(baseKey(entry.getKey())))
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (first, ignored) -> first,
                        java.util.LinkedHashMap::new));
    }

    private Set<String> allowedKeys(PendingAction pending) {
        Set<String> keys = new HashSet<>();
        if ("MOVIE_SCHEDULING".equalsIgnoreCase(pending.type()) || "MOVIE".equalsIgnoreCase(pending.workflowType())) {
            keys.addAll(Set.of("startTime", "endTime", "timeRange", "locationScope", "headcount",
                    "maxEndTime", "durationMinutes", "minDurationMinutes", "maxDurationMinutes"));
            return keys;
        }
        if ("PLAN_SLOT_FILLING".equalsIgnoreCase(pending.type())
                || "DINING_LOCKED_PLAN".equalsIgnoreCase(pending.workflowType())) {
            keys.addAll(Set.of("startTime", "endTime", "timeRange", "locationScope", "headcount",
                    "maxEndTime", "durationMinutes", "minDurationMinutes", "maxDurationMinutes",
                    "orderPreference", "pace", "budgetLevel", "preferredTransportMode"));
            return keys;
        }
        for (SlotName slotName : SlotName.values()) {
            keys.add(slotKey(slotName));
        }
        keys.addAll(Set.of("minDurationMinutes", "maxDurationMinutes", "durationMinutes",
                "maxEndTime", "preferredTransportMode"));
        return keys;
    }

    private String baseKey(String key) {
        if (key == null) return "";
        int index = key.indexOf(':');
        return index >= 0 ? key.substring(index + 1) : key;
    }

    private String slotKey(SlotName slotName) {
        return switch (slotName) {
            case HEADCOUNT -> "headcount";
            case START_TIME -> "startTime";
            case END_TIME -> "endTime";
            case TIME_RANGE -> "timeRange";
            case DURATION_RANGE -> "durationMinutes";
            case MAX_END_TIME -> "maxEndTime";
            case LOCATION_SCOPE -> "locationScope";
            case ORDER_PREFERENCE -> "orderPreference";
            case PACE -> "pace";
            case BUDGET_LEVEL -> "budgetLevel";
            case TRANSPORT_MODE -> "preferredTransportMode";
            case SEARCH_TAG -> "searchTag";
            case SEARCH_CATEGORY -> "searchCategory";
        };
    }
}
