package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.provider.PoiProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Component
public class ReplacementSearchEngine {

    private final PoiProvider poiDatabase;
    private final PlanningToolOrchestrator planningToolOrchestrator;

    @Autowired
    public ReplacementSearchEngine(PoiProvider poiDatabase, PlanningToolOrchestrator planningToolOrchestrator) {
        this.poiDatabase = poiDatabase;
        this.planningToolOrchestrator = planningToolOrchestrator;
    }

    public ReplacementSearchEngine(PoiProvider poiDatabase) {
        this(poiDatabase, new PlanningToolOrchestrator(poiDatabase));
    }

    public Optional<PoiDto> findReplacement(PlanStep target, PlanPatch patch, PlanIntent intent, Set<String> usedIds) {
        Optional<String> selectedPoiId = selectedPoiId(patch);
        if (selectedPoiId.isPresent()) {
            return poiDatabase.findById(selectedPoiId.get())
                    .filter(poi -> !usedIds.contains(poi.poiId()) || poi.poiId().equals(target.poiId()))
                    .filter(poi -> isAllowed(poi, patch, intent));
        }

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
        Optional<String> selectedPoiId = selectedPoiId(patch);
        if (selectedPoiId.isPresent()) {
            return poiDatabase.findById(selectedPoiId.get())
                    .filter(poi -> !usedIds.contains(poi.poiId()))
                    .filter(poi -> isAllowed(poi, patch, intent));
        }

        return findCandidates(phase, patch, intent, usedIds, 1).stream().findFirst();
    }

    public List<PoiDto> findCandidates(String phase, PlanPatch patch, PlanIntent intent, Set<String> usedIds, int limit) {
        String normalizedPhase = normalizePhase(phase);
        String category = categoryForPhase(normalizedPhase);
        List<String> tags = tagsForPatch(phase, patch, intent);
        int radius = patch.requirements().prefer().contains("NEARBY") || "WALK".equalsIgnoreCase(intent.preferredTransportMode()) ? 3 : 5;
        List<SearchTask> tasks = List.of(
                new SearchTask("PATCH-1", normalizedPhase, category, tags, radius, Math.max(3, limit * 3), 10, "patch preference"),
                new SearchTask("PATCH-2", normalizedPhase, category, List.of(), radius, Math.max(3, limit * 3), 90, "patch fallback")
        );
        CandidatePool pool = planningToolOrchestrator.collectCandidates("patch", intent, tasks);
        return pool.candidatesFor(normalizedPhase).stream()
                .map(CandidateProfile::poi)
                .filter(poi -> usedIds == null || !usedIds.contains(poi.poiId()))
                .filter(poi -> isAllowed(poi, patch, intent))
                .sorted(Comparator.comparingDouble((PoiDto poi) -> scoreCandidate(poi, patch, intent)).reversed())
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<String> tagsForPatch(String phase, PlanPatch patch, PlanIntent intent) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String prefer : patch.requirements().prefer()) {
            if (prefer.startsWith("SELECTED_POI:")) continue;
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
            if (normalized.startsWith("selected_poi:")) continue;
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

    public Optional<String> selectedPoiId(PlanPatch patch) {
        if (patch == null || patch.requirements() == null) return Optional.empty();
        return patch.requirements().prefer().stream()
                .filter(value -> value != null && value.startsWith("SELECTED_POI:"))
                .map(value -> value.substring("SELECTED_POI:".length()).trim())
                .filter(value -> !value.isBlank())
                .findFirst();
    }
}
