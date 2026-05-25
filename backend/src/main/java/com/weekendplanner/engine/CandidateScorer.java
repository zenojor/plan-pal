package com.weekendplanner.engine;

import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.PoiDto;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
public class CandidateScorer {

    public double score(PoiDto poi, String phase, PlanIntent intent) {
        double distancePenalty = "WALK".equalsIgnoreCase(intent.preferredTransportMode()) ? 18.0
                : "DRIVE".equalsIgnoreCase(intent.preferredTransportMode()) ? 5.0 : 10.0;
        double score = 100.0 - poi.distanceKm() * distancePenalty;
        score -= Math.abs(poi.recommendedDurationMinutes() - preferredDuration(phase, intent)) * 0.2;

        Set<String> tags = normalizedTags(poi);
        String prompt = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
        if (matchesTerms(poi, intent.avoid())) score -= 120;
        if (matchesTerms(poi, intent.mustHave())) score += 45;
        if ("LOW".equalsIgnoreCase(intent.budgetLevel())) {
            score += match(tags, "free", "quick", "casual") * 18;
            score -= match(tags, "premium", "fine", "cocktail") * 12;
        } else if ("HIGH".equalsIgnoreCase(intent.budgetLevel())) {
            score += match(tags, "cocktail", "wine", "exhibition", "premium") * 12;
        }
        if (intent.weatherSensitive()) {
            score += match(tags, "indoor") * 25;
            score -= match(tags, "outdoor", "citywalk") * 30;
        }
        if (intent.hasChildren()) {
            score += match(tags, "child", "family", "science", "sports", "indoor") * 22;
            score -= match(tags, "club", "nightclub", "adult") * 80;
        }
        if ("DRINKS".equals(phase)) {
            score += match(tags, "bar", "drink", "cocktail", "pub", "wine", "night", "social", "casual") * 18;
            if (contains(prompt, "club", "nightclub", "韫﹁开", "澶滃簵")) score += match(tags, "club", "dance", "nightclub") * 35;
        } else if ("DINING".equals(phase)) {
            if (contains(prompt, "鍐版矙", "鏋滄眮", "濂惰尪", "鐢滃搧", "鍜栧暋", "��ɳ")) {
                score += match(tags, "smoothie", "juice", "tea", "dessert", "coffee") * 35;
            }
            if (contains(prompt, "bbq", "grill", "鐑х儰", "鐑や覆", "�տ�")) score += match(tags, "bbq", "grill", "late", "street") * 35;
            if (contains(prompt, "鍚冭荆", "杈?", "宸濊彍", "婀樿彍", "鐏攨", "灏忛緳", "���") && !hasConstraint(intent, "NO_SPICY")) {
                score += match(tags, "spicy", "sichuan", "hunan", "hotpot", "crayfish") * 32;
            }
            if ("DATE".equalsIgnoreCase(intent.sceneType())) {
                score += match(tags, "quiet", "romantic", "photo", "dessert", "wine", "bistro") * 20;
                score -= match(tags, "child", "family", "sports", "club") * 30;
            } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                score += match(tags, "social", "party", "hotpot", "street", "casual") * 16;
            } else {
                score += match(tags, "light", "healthy", "vegan", "quick", "family", "quiet") * 14;
            }
            if (hasConstraint(intent, "NO_SPICY")) {
                score -= match(tags, "spicy", "hotpot") * 60;
                score += match(tags, "cantonese", "light", "healthy", "normal", "family", "quiet") * 12;
            }
        } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "quiet", "romantic", "photo", "dessert", "wine", "exhibition", "movie", "citywalk") * 20;
            score -= match(tags, "child", "family", "sports", "club", "dance") * 40;
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "social", "exhibition", "citywalk", "movie", "team", "photo") * 15;
        } else if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
            score += match(tags, "child", "indoor", "science", "sports", "free", "outdoor") * 14;
        } else {
            score += match(tags, "solo_friendly", "quiet", "coffee", "citywalk", "museum", "bookstore") * 15;
        }
        return score;
    }

    public boolean isAllowed(PoiDto poi, PlanIntent intent) {
        if (poi == null) return false;
        Set<String> tags = normalizedTags(poi);
        if (!"SOCIAL".equalsIgnoreCase(intent.sceneType()) && !"DATE".equalsIgnoreCase(intent.sceneType())
                && tags.contains("adult_only")) return false;
        if (intent.hasChildren() && tags.contains("adult_only")) return false;
        if (("SOCIAL".equalsIgnoreCase(intent.sceneType()) || "DATE".equalsIgnoreCase(intent.sceneType()))
                && !intent.hasChildren() && isChildOnlyVenue(tags)) return false;
        if (matchesTerms(poi, intent.avoid())) return false;
        return !hasConstraint(intent, "NO_SPICY") || match(tags, "spicy", "hotpot") == 0;
    }

    public List<String> matchedTags(PoiDto poi, SearchTask task) {
        Set<String> tags = normalizedTags(poi);
        return task.tags().stream()
                .filter(tag -> tags.stream().anyMatch(poiTag ->
                        poiTag.equalsIgnoreCase(tag) || poiTag.contains(tag.toLowerCase(Locale.ROOT))))
                .toList();
    }

    private int preferredDuration(String phase, PlanIntent intent) {
        int base = switch (phase) {
            case "DINING" -> 60;
            case "DRINKS" -> 75;
            case "LEISURE" -> 60;
            default -> 90;
        };
        if ("RELAXED".equalsIgnoreCase(intent.pace())) return Math.round(base * 1.15f);
        if ("COMPACT".equalsIgnoreCase(intent.pace())) return Math.max(35, Math.round(base * 0.8f));
        return base;
    }

    private boolean hasConstraint(PlanIntent intent, String constraint) {
        return intent.dietaryConstraints() != null
                && intent.dietaryConstraints().stream().anyMatch(c -> c.equalsIgnoreCase(constraint));
    }

    private boolean matchesTerms(PoiDto poi, List<String> terms) {
        if (poi == null || terms == null || terms.isEmpty()) return false;
        String name = poi.name() == null ? "" : poi.name().toLowerCase(Locale.ROOT);
        Set<String> tags = normalizedTags(poi);
        for (String term : terms) {
            if (term == null || term.isBlank()) continue;
            String normalized = term.toLowerCase(Locale.ROOT).trim();
            if (name.contains(normalized)) return true;
            for (String tag : tags) {
                if (tag.contains(normalized) || normalized.contains(tag)) return true;
            }
        }
        return false;
    }

    private boolean isChildOnlyVenue(Set<String> tags) {
        boolean hasChildTag = tags.stream().anyMatch(t -> t.contains("child") || t.contains("kids"));
        boolean hasSocialTag = tags.stream().anyMatch(t -> t.contains("social") || t.contains("adult") || t.contains("bar"));
        return hasChildTag && !hasSocialTag;
    }

    private Set<String> normalizedTags(PoiDto poi) {
        Set<String> tags = new HashSet<>();
        if (poi.tags() == null) return tags;
        for (String tag : poi.tags()) {
            tags.add(tag == null ? "" : tag.toLowerCase(Locale.ROOT));
        }
        return tags;
    }

    private int match(Set<String> tags, String... needles) {
        int count = 0;
        for (String needle : needles) {
            for (String tag : tags) {
                if (tag.contains(needle)) {
                    count++;
                    break;
                }
            }
        }
        return count;
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
