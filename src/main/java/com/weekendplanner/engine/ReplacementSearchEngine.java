package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.mock.MockPoiDatabase;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ReplacementSearchEngine {

    private final MockPoiDatabase poiDatabase;

    public ReplacementSearchEngine(MockPoiDatabase poiDatabase) {
        this.poiDatabase = poiDatabase;
    }

    public Optional<PoiDto> findReplacement(PlanStep target, PlanPatch patch, PlanIntent intent, Set<String> usedIds) {
        String phase = normalizePhase(firstNonBlank(patch.target().phase(), patch.target().activityType(), target.phase()));
        if (("ACTIVITY".equalsIgnoreCase(target.phase()) || "LEISURE".equalsIgnoreCase(target.phase()))
                && (patch.requirements().prefer().contains("CHILD_FRIENDLY")
                || patch.requirements().prefer().contains("INDOOR")
                || patch.requirements().avoid().contains("MALL"))) {
            phase = target.phase();
        }
        return findCandidate(phase, patch, intent, usedIds);
    }

    public Optional<PoiDto> findCandidate(String phase, PlanPatch patch, PlanIntent intent, Set<String> usedIds) {
        String category = categoryForPhase(phase);
        List<String> tags = tagsForPatch(phase, patch, intent);
        int radius = patch.requirements().prefer().contains("NEARBY") || "WALK".equalsIgnoreCase(intent.preferredTransportMode()) ? 3 : 5;
        List<PoiDto> candidates = poiDatabase.searchByCategory(category, tags, radius);
        if (candidates.isEmpty()) {
            candidates = poiDatabase.searchByCategory(category, List.of(), radius);
        }
        return candidates.stream()
                .filter(poi -> !usedIds.contains(poi.poiId()))
                .filter(poi -> isAllowed(poi, patch, intent))
                .max(Comparator.comparingDouble(poi -> scoreCandidate(poi, patch, intent)));
    }

    private List<String> tagsForPatch(String phase, PlanPatch patch, PlanIntent intent) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String prefer : patch.requirements().prefer()) {
            switch (prefer) {
                case "INDOOR" -> tags.add("indoor");
                case "QUIET" -> tags.add("quiet");
                case "CHILD_FRIENDLY" -> tags.add("child_friendly");
                case "PARK" -> tags.add("park");
                default -> {
                    if (!"NEARBY".equals(prefer)) tags.add(prefer.toLowerCase(Locale.ROOT));
                }
            }
        }
        if (intent.hasChildren()) tags.add("child_friendly");
        if ("DRINKS".equals(phase)) tags.add("bar");
        if ("DINING".equals(phase)) tags.add("social_dining");
        if ("ACTIVITY".equals(phase) && tags.isEmpty()) tags.add("indoor");
        return List.copyOf(tags);
    }

    private boolean isAllowed(PoiDto poi, PlanPatch patch, PlanIntent intent) {
        String haystack = (poi.name() + " " + String.join(" ", poi.tags())).toLowerCase(Locale.ROOT);
        for (String avoid : patch.requirements().avoid()) {
            String normalized = avoid.toLowerCase(Locale.ROOT);
            if ("mall".equals(normalized) && (haystack.contains("mall") || haystack.contains("商场"))) return false;
            if ("outdoor".equals(normalized) && haystack.contains("outdoor")) return false;
            if ("club".equals(normalized) && haystack.contains("club")) return false;
            if (haystack.contains(normalized)) return false;
        }
        if (intent.hasChildren() && haystack.contains("adult_only")) return false;
        return true;
    }

    private double scoreCandidate(PoiDto poi, PlanPatch patch, PlanIntent intent) {
        double score = 100 - poi.distanceKm() * 8;
        String tags = String.join(" ", poi.tags()).toLowerCase(Locale.ROOT);
        for (String prefer : patch.requirements().prefer()) {
            String normalized = prefer.toLowerCase(Locale.ROOT);
            if ("nearby".equals(normalized)) score -= poi.distanceKm() * 8;
            if ("child_friendly".equals(normalized) && tags.contains("child_friendly")) score += 25;
            if ("indoor".equals(normalized) && tags.contains("indoor")) score += 20;
            if ("quiet".equals(normalized) && tags.contains("quiet")) score += 15;
        }
        if (intent.hasChildren() && tags.contains("child_friendly")) score += 20;
        return score;
    }

    private String categoryForPhase(String phase) {
        return "ACTIVITY".equals(phase) || "LEISURE".equals(phase) ? "ACTIVITY" : "RESTAURANT";
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return "LEISURE";
        String normalized = phase.toUpperCase(Locale.ROOT);
        if ("RESTAURANT".equals(normalized)) return "DINING";
        if ("BAR".equals(normalized)) return "DRINKS";
        return normalized;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }
}
