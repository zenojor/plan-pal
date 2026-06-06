package com.weekendplanner.engine.planning;



import com.weekendplanner.engine.candidate.CandidatePool;
import com.weekendplanner.engine.candidate.CandidateProfile;
import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PlanPatch;
import com.weekendplanner.dto.PlanStep;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.provider.PoiProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
                    .filter(poi -> target == null || !poi.poiId().equals(target.poiId()))
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
        List<String> requiredTags = requiredTagsForPatch(patch);
        boolean strictTags = patch.requirements().prefer().stream()
                .anyMatch(value -> "STRICT_TAGS".equalsIgnoreCase(value));
        int radius = patch.requirements().prefer().contains("NEARBY") || "WALK".equalsIgnoreCase(intent.preferredTransportMode()) ? 3 : 5;
        List<SearchTask> tasks = List.of(
                new SearchTask("PATCH-1", normalizedPhase, category, tags, radius, Math.max(3, limit * 3), 10, "patch preference"),
                new SearchTask("PATCH-2", normalizedPhase, category, List.of(), radius, Math.max(3, limit * 3), 90, "patch fallback")
        );
        CandidatePool pool = planningToolOrchestrator.collectCandidates("patch", intent, tasks);
        List<PoiDto> candidates = pool.candidatesFor(normalizedPhase).stream()
                .map(CandidateProfile::poi)
                .filter(poi -> usedIds == null || !usedIds.contains(poi.poiId()))
                .filter(poi -> isAllowed(poi, patch, intent))
                .filter(poi -> !strictTags || matchesRequiredTags(poi, requiredTags))
                .sorted(Comparator.comparingDouble((PoiDto poi) -> scoreCandidate(poi, phase, patch, intent)).reversed())
                .limit(Math.max(1, limit))
                .toList();
        if (!candidates.isEmpty()) return candidates;
        if (strictTags) return List.of();

        List<PoiDto> widened = new ArrayList<>();
        widened.addAll(directSearch(category, List.of(), Math.max(radius, 8), usedIds, patch, intent));
        if (widened.isEmpty()) {
            widened.addAll(directSearch(category, List.of(), 12, usedIds, patch, intent));
        }
        if (widened.isEmpty() && ("ACTIVITY".equals(normalizedPhase) || "LEISURE".equals(normalizedPhase))) {
            widened.addAll(directSearch("SHOPPING", List.of(), 12, usedIds, patch, intent));
        }
        Map<String, PoiDto> deduped = new LinkedHashMap<>();
        widened.stream()
                .sorted(Comparator.comparingDouble((PoiDto poi) -> scoreCandidate(poi, phase, patch, intent)).reversed())
                .forEach(poi -> deduped.putIfAbsent(poi.poiId(), poi));
        return deduped.values().stream()
                .limit(Math.max(1, limit))
                .toList();
    }

    private List<PoiDto> directSearch(String category,
                                      List<String> tags,
                                      int radius,
                                      Set<String> usedIds,
                                      PlanPatch patch,
                                      PlanIntent intent) {
        return poiDatabase.searchByCategory(category, tags, radius).stream()
                .filter(poi -> usedIds == null || !usedIds.contains(poi.poiId()))
                .filter(poi -> isAllowed(poi, patch, intent))
                .toList();
    }

    private List<String> tagsForPatch(String phase, PlanPatch patch, PlanIntent intent) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String prefer : patch.requirements().prefer()) {
            if ("EXPAND_RANGE".equals(prefer) || "FLEXIBLE_ACTIVITY".equals(prefer)) continue;
            if ("STRICT_TAGS".equals(prefer)) continue;
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

    private List<String> requiredTagsForPatch(PlanPatch patch) {
        if (patch == null || patch.requirements() == null) return List.of();
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        for (String prefer : patch.requirements().prefer()) {
            if (prefer == null || prefer.isBlank()) continue;
            if (prefer.startsWith("SELECTED_POI:")) continue;
            String normalized = prefer.trim().toLowerCase(Locale.ROOT);
            if (Set.of("strict_tags", "nearby", "expand_range", "flexible_activity").contains(normalized)) continue;
            switch (normalized) {
                case "indoor" -> tags.add("indoor");
                case "quiet" -> tags.add("quiet");
                case "child_friendly" -> tags.add("child_friendly");
                case "park" -> tags.add("park");
                default -> tags.add(normalized);
            }
        }
        return List.copyOf(tags);
    }

    private boolean matchesRequiredTags(PoiDto poi, List<String> requiredTags) {
        if (requiredTags == null || requiredTags.isEmpty()) return true;
        String haystack = (poi.name() + " " + String.join(" ", poi.tags())).toLowerCase(Locale.ROOT);
        for (String tag : requiredTags) {
            if (tag == null || tag.isBlank()) continue;
            if (!haystack.contains(tag.toLowerCase(Locale.ROOT))) return false;
        }
        return true;
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
        Optional<String> explicitPoiId = selectedPoiId(patch);
        boolean isExplicit = explicitPoiId.isPresent() && explicitPoiId.get().equals(poi.poiId());
        if (!isExplicit) {
            if (!TimeUtils.isOpenDuringWindow(poi.businessHours(), intent.startTime(), intent.endTime())) {
                return false;
            }
        }
        return true;
    }

    private double scoreCandidate(PoiDto poi, String phase, PlanPatch patch, PlanIntent intent) {
        double score = 100 - poi.distanceKm() * 8;
        String tags = String.join(" ", poi.tags()).toLowerCase(Locale.ROOT);
        String category = poi.category() != null ? poi.category().toUpperCase(Locale.ROOT) : "";
        
        // 核心修正：通用同类型商户强匹配与惩罚机制
        String normalizedPhase = normalizePhase(phase);
        if ("DRINKS".equals(normalizedPhase)) {
            // DRINKS 小酌/酒吧阶段：强匹配带有酒吧/饮酒等夜生活标签的商户，排除纯餐馆及活动
            boolean isBar = tags.contains("bar") || tags.contains("drinks") || tags.contains("cocktail") 
                    || tags.contains("pub") || tags.contains("wine") || tags.contains("beer") 
                    || tags.contains("nightlife") || tags.contains("club") || tags.contains("livehouse");
            if ("RESTAURANT".equals(category) && isBar) {
                score += 120;
            } else {
                score -= 120;
            }
        } else if ("DINING".equals(normalizedPhase)) {
            // DINING 餐饮阶段：强匹配非酒吧餐饮商户，排除纯酒馆及活动
            boolean isPureBar = (tags.contains("bar") || tags.contains("club") || tags.contains("nightclub") || tags.contains("livehouse"))
                    && !tags.contains("social_dining") && !tags.contains("family_style") && !tags.contains("chinese") && !tags.contains("spicy");
            if ("RESTAURANT".equals(category) && !isPureBar) {
                score += 120;
            } else {
                score -= 120;
            }
        } else if ("ACTIVITY".equals(normalizedPhase) || "LEISURE".equals(normalizedPhase)) {
            // ACTIVITY/LEISURE 活动娱乐阶段：强匹配活动设施商户，排除一切餐厅和酒馆
            if ("ACTIVITY".equals(category)) {
                score += 120;
            } else {
                score -= 120;
            }
        }
        
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
