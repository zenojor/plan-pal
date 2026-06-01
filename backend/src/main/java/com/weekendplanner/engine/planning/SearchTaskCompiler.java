package com.weekendplanner.engine.planning;


import com.weekendplanner.dto.PlanIntent;
import com.weekendplanner.dto.WeatherSnapshot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class SearchTaskCompiler {

    @Value("${agent.default-radius-km:3}")
    private int defaultRadiusKm = 3;

    @Value("${agent.max-radius-km:5}")
    private int maxRadiusKm = 5;

    @Value("${agent.fast.search-limit-per-task:8}")
    private int defaultLimit = 8;

    public List<SearchTask> compile(PlanIntent intent, List<String> phases) {
        return compile(intent, phases, null);
    }

    public List<SearchTask> compile(PlanIntent intent, List<String> phases, WeatherSnapshot weather) {
        Map<String, SearchTask> deduped = new LinkedHashMap<>();
        int index = 0;
        for (String rawPhase : phases) {
            String phase = normalizePhase(rawPhase);
            String category = categoryForPhase(phase);
            int baseRadius = baseRadius(intent);
            List<List<String>> tagGroups = tagGroupsFor(category, phase, intent, weather);
            index = addTask(deduped, index, phase, category, tagGroups.get(0), baseRadius, 10, "strong preference");
            index = addTask(deduped, index, phase, category, tagGroups.get(1), baseRadius, 20, "weak preference");
            int expandedRadius = Math.max(baseRadius, maxRadiusKm);
            if (expandedRadius > baseRadius) {
                index = addTask(deduped, index, phase, category, tagGroups.get(0), expandedRadius, 30, "expanded strong preference");
                index = addTask(deduped, index, phase, category, tagGroups.get(1), expandedRadius, 40, "expanded weak preference");
            }
            index = addTask(deduped, index, phase, category, List.of(), expandedRadius, 90, "empty-tag fallback");
        }
        return List.copyOf(deduped.values());
    }

    public List<SearchTask> compileConsulting(PlanIntent intent, List<String> promptTags) {
        List<String> phases = List.of("ACTIVITY", "DINING", "DRINKS");
        List<SearchTask> base = compile(intent, phases);
        if (promptTags == null || promptTags.isEmpty()) {
            return base;
        }
        Map<String, SearchTask> tasks = new LinkedHashMap<>();
        for (SearchTask task : base) {
            tasks.put(key(task), task);
        }
        int index = tasks.size();
        for (String phase : phases) {
            index = addTask(tasks, index, phase, categoryForPhase(phase), promptTags, maxRadiusKm, 5, "consulting prompt tags");
        }
        return List.copyOf(tasks.values());
    }

    private int addTask(Map<String, SearchTask> tasks, int index, String phase, String category,
                        List<String> tags, int radiusKm, int priority, String reason) {
        SearchTask task = new SearchTask(
                "T" + (++index),
                phase,
                category,
                distinct(tags),
                radiusKm,
                defaultLimit,
                priority,
                reason);
        tasks.putIfAbsent(key(task), task);
        return index;
    }

    private String key(SearchTask task) {
        return task.phase() + "|" + task.category() + "|" + task.radiusKm() + "|" + String.join(",", task.tags());
    }

    private int baseRadius(PlanIntent intent) {
        int radius = "WIDE".equalsIgnoreCase(intent.locationScope()) ? maxRadiusKm : defaultRadiusKm;
        if ("WALK".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.min(radius, Math.max(1, defaultRadiusKm));
        }
        if ("DRIVE".equalsIgnoreCase(intent.preferredTransportMode())) {
            return Math.max(radius, maxRadiusKm);
        }
        return radius;
    }

    private List<List<String>> tagGroupsFor(String category, String phase, PlanIntent intent, WeatherSnapshot weather) {
        List<String> strong;
        List<String> weak;
        String prompt = intent.originalPrompt() == null ? "" : intent.originalPrompt().toLowerCase(Locale.ROOT);
        if ("DRINKS".equals(phase)) {
            if (contains(prompt, "club", "nightclub", "韫﹁开", "澶滃簵")) {
                strong = List.of("club", "nightclub", "dance", "late_night");
                weak = List.of("livehouse", "bar", "nightlife", "cocktail");
            } else if (contains(prompt, "quiet", "bar", "瀹夐潤", "娓呭惂", "涓嶅惖")) {
                strong = List.of("quiet_bar", "wine", "cocktail", "solo_friendly");
                weak = List.of("bar", "craft_beer", "drinks", "nightlife");
            } else {
                strong = List.of("bar", "drinks", "cocktail", "pub", "wine", "nightlife", "social_dining");
                weak = List.of("craft_beer", "quiet_bar", "social_dining", "casual", "party");
            }
        } else if ("RESTAURANT".equals(category)) {
            if (contains(prompt, "鍐版矙", "鏋滄眮", "濂惰尪", "鐢滃搧", "鍜栧暋", "��ɳ")) {
                strong = List.of("smoothie", "dessert", "juice", "tea", "coffee");
                weak = List.of("quick_bite", "solo_friendly", "casual");
            } else if (contains(prompt, "bbq", "grill", "鐑х儰", "鐑や覆", "�տ�")) {
                strong = List.of("bbq", "late_night", "social_dining");
                weak = List.of("spicy", "street_food", "casual", "party");
            } else if (contains(prompt, "鍚冭荆", "杈?", "宸濊彍", "婀樿彍", "鐏攨", "灏忛緳", "���")) {
                strong = List.of("spicy", "sichuan", "hunan", "hotpot", "crayfish");
                weak = List.of("bbq", "late_night", "social_dining", "party");
            } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
                strong = List.of("social_dining", "quiet", "romantic");
                weak = List.of("dessert", "coffee", "wine", "bistro", "casual");
            } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
                strong = List.of("social_dining");
                weak = List.of("party", "casual", "hotpot", "street_food", "normal", "bbq");
            } else if ("SOLO".equalsIgnoreCase(intent.sceneType())) {
                strong = List.of("bbq", "spicy", "hotpot", "social_dining", "smoothie", "juice");
                weak = List.of("healthy", "casual");
            } else {
                strong = List.of("dietary_type=light", "healthy", "vegan", "quick_bite", "family_style");
                weak = List.of("healthy", "family_style", "normal", "chinese", "quiet");
            }
        } else if ("DATE".equalsIgnoreCase(intent.sceneType())) {
            strong = List.of("quiet_bar", "dessert", "coffee", "photo", "exhibition");
            weak = List.of("citywalk", "movie", "wine", "romantic", "solo_friendly");
        } else if ("SOCIAL".equalsIgnoreCase(intent.sceneType())) {
            strong = List.of("social_entertainment");
            weak = List.of("exhibition", "citywalk", "movie", "team", "photo", "indoor", "outdoor");
        } else if ("FAMILY".equalsIgnoreCase(intent.sceneType())) {
            strong = List.of("child_friendly");
            weak = List.of("indoor", "outdoor", "science", "sports", "free");
        } else {
            strong = List.of("solo_friendly", "quiet_bar", "coffee");
            weak = List.of("citywalk", "casual", "tea", "museum", "bookstore");
        }

        if (intent.hasChildren()) {
            strong = merge(List.of("child_friendly", "indoor", "science", "sports"), strong);
            weak = merge(List.of("family_style", "free"), weak);
        }
        if (intent.weatherSensitive()) strong = merge(List.of("indoor"), strong);
        if (hasWeatherRisk(weather)) strong = merge(weather.preferredTags(), strong);
        if ("LOW".equalsIgnoreCase(intent.budgetLevel())) weak = merge(List.of("free", "quick_bite", "casual"), weak);
        if (intent.mustHave() != null && !intent.mustHave().isEmpty()) strong = merge(intent.mustHave(), strong);
        return List.of(strong, weak);
    }

    private boolean hasWeatherRisk(WeatherSnapshot weather) {
        return weather != null
                && ("MEDIUM".equalsIgnoreCase(weather.outdoorRiskLevel())
                || "HIGH".equalsIgnoreCase(weather.outdoorRiskLevel()));
    }

    private List<String> merge(List<String> preferred, List<String> existing) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (preferred != null) merged.addAll(preferred);
        if (existing != null) merged.addAll(existing);
        return List.copyOf(merged);
    }

    private List<String> distinct(List<String> tags) {
        return tags == null ? List.of() : new ArrayList<>(new LinkedHashSet<>(tags));
    }

    private boolean contains(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    private String normalizePhase(String phase) {
        if (phase == null || phase.isBlank()) return "LEISURE";
        String normalized = phase.toUpperCase(Locale.ROOT);
        if ("RESTAURANT".equals(normalized)) return "DINING";
        if ("BAR".equals(normalized)) return "DRINKS";
        if ("EVENING".equals(normalized)) return "LEISURE";
        return normalized;
    }

    private String categoryForPhase(String phase) {
        return "ACTIVITY".equals(phase) || "LEISURE".equals(phase) ? "ACTIVITY" : "RESTAURANT";
    }
}
