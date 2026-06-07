package com.weekendplanner.engine.workflow;


import com.weekendplanner.dto.ConstraintSet;
import com.weekendplanner.dto.ExperiencePreference;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class ContextualResearchPlanner {

    public SearchPlan plan(String sceneType, ConstraintSet constraints) {
        ExperiencePreference preference = constraints == null ? ExperiencePreference.empty() : constraints.experiencePreference();
        if (preference == null || preference.isEmpty()) {
            return SearchPlan.needsMoreContext("先选一个偏好方向，我再帮你找候选。");
        }
        if (blank(preference.timeHint())) {
            return SearchPlan.needsMoreContext("大概什么时候出发？比如下午、今晚，或者一个具体时间。");
        }
        if (blank(preference.locationHint())) {
            return SearchPlan.needsMoreContext("你希望在哪个区域附近？可以说商圈、地铁站，或者直接说“附近”。");
        }

        // Merge constraints' avoid list into preference's avoid list to keep user intent
        java.util.List<String> combinedAvoid = new java.util.ArrayList<>(preference.avoid());
        if (constraints != null && constraints.avoid() != null) {
            combinedAvoid.addAll(constraints.avoid());
        }
        if (constraints != null && (constraints.weatherSensitive() || constraints.avoid().contains("weather_risk"))) {
            combinedAvoid.add("weather_risk");
            combinedAvoid.add("outdoor");
        }

        ExperiencePreference enrichedPreference = new ExperiencePreference(
            preference.moods(),
            preference.interactionLevel(),
            preference.formalityLevel(),
            preference.budgetMood(),
            (preference.weatherPolicy() == null && constraints != null && constraints.weatherSensitive()) ? "indoor_first" : preference.weatherPolicy(),
            preference.activityBiases(),
            combinedAvoid,
            preference.timeHint(),
            preference.locationHint()
        );

        String scene = normalize(sceneType);
        String mood = primaryMood(enrichedPreference);
        if ("FAMILY".equals(scene)) {
            return familyPlan(mood, enrichedPreference);
        }
        if ("DATE".equals(scene) || enrichedPreference.avoid().contains("awkward_silence")) {
            return datePlan(mood, enrichedPreference);
        }
        return generalPlan(mood, enrichedPreference);
    }

    private SearchPlan datePlan(String mood, ExperiencePreference preference) {
        if ("ritual".equals(mood)) {
            return new SearchPlan(false, null, "更有氛围但不压迫的选择",
                    "我会优先找有氛围、好接话、不会太正式的候选。",
                    List.of(
                            new SearchQuery("DINING", "RESTAURANT", List.of("cocktail", "quiet_bar", "dessert", "wine")),
                            new SearchQuery("ACTIVITY", "ACTIVITY", List.of("exhibition", "photo", "indoor"))
                    ),
                    mergeAvoid(preference, List.of("child_friendly", "club", "nightclub", "adult_only")),
                    weights(Map.of(
                            "cocktail", 42.0, "quiet_bar", 45.0, "dessert", 36.0, "wine", 30.0,
                            "exhibition", 34.0, "photo", 28.0, "indoor", 10.0,
                            "citywalk", -28.0, "child_friendly", -45.0, "club", -55.0)));
        }
        if ("budget_friendly".equals(mood)) {
            return new SearchPlan(false, null, "预算友好又不随便的选择",
                    "我会优先找近、轻松、低消费但不敷衍的候选。",
                    List.of(
                            new SearchQuery("ACTIVITY", "ACTIVITY", List.of("free", "citywalk", "exhibition")),
                            new SearchQuery("DINING", "RESTAURANT", List.of("dessert", "coffee", "quick_bite"))
                    ),
                    mergeAvoid(preference, List.of("club", "nightclub", "too_expensive")),
                    weights(Map.of("free", 38.0, "dessert", 26.0, "coffee", 24.0, "quick_bite", 18.0, "club", -45.0)));
        }
        if ("topic_safe".equals(mood)) {
            List<SearchQuery> queries = new java.util.ArrayList<>();
            if (preference.activityBiases().contains("movie")) {
                queries.add(new SearchQuery("ACTIVITY", "CINEMA", List.of("movie", "indoor")));
                queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", List.of("movie", "exhibition")));
            } else {
                queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", List.of("exhibition", "movie", "indoor")));
            }
            queries.add(new SearchQuery("DINING", "RESTAURANT", List.of("dessert", "quiet")));

            return new SearchPlan(false, null, "有话题但不尴尬的选择",
                    "我会优先找自然制造话题、又不用一直硬聊的候选。",
                    queries,
                    mergeAvoid(preference, List.of("club", "nightclub", "too_loud")),
                    weights(Map.of("exhibition", 42.0, "movie", 32.0, "indoor", 16.0, "dessert", 20.0, "quiet", 18.0)));
        }
        return new SearchPlan(false, null, "轻松低压力的选择",
                "我会优先找好聊天、方便撤退、节奏轻的候选。",
                List.of(
                        new SearchQuery("DINING", "RESTAURANT", List.of("coffee", "dessert", "quiet")),
                        new SearchQuery("ACTIVITY", "ACTIVITY", List.of("citywalk", "exhibition", "outdoor"))
                ),
                mergeAvoid(preference, List.of("club", "nightclub", "adult_only")),
                weights(Map.of("coffee", 35.0, "dessert", 32.0, "quiet", 24.0, "citywalk", 18.0, "exhibition", 16.0)));
    }

    private SearchPlan familyPlan(String mood, ExperiencePreference preference) {
        boolean indoorFirst = "indoor_first".equals(preference.weatherPolicy()) || "weather_safe".equals(mood);
        List<String> extraAvoid = new java.util.ArrayList<>(List.of("bar", "club", "nightlife", "adult_only", "drinks"));
        if (indoorFirst) {
            extraAvoid.add("outdoor");
            extraAvoid.add("citywalk");
        }
        Map<String, Double> baseWeights = new java.util.LinkedHashMap<>(Map.of(
                "child_friendly", 48.0, "science", 32.0, "family_style", 30.0,
                "dessert", 18.0, "bar", -80.0, "nightlife", -80.0, "drinks", -60.0));
        if (indoorFirst) {
            baseWeights.put("indoor", 40.0);
            baseWeights.put("outdoor", -70.0);
            baseWeights.put("citywalk", -50.0);
        }
        List<SearchQuery> queries = new java.util.ArrayList<>();
        if (preference.activityBiases().contains("movie")) {
            queries.add(new SearchQuery("ACTIVITY", "CINEMA", List.of("movie", "indoor")));
            queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", List.of("movie", "child_friendly")));
            baseWeights.put("movie", 50.0);
            baseWeights.put("cinema", 50.0);
        } else {
            queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", List.of("child_friendly", "science", "indoor", "photo")));
        }
        queries.add(new SearchQuery("DINING", "RESTAURANT", List.of("family_style", "dessert")));

        return new SearchPlan(false, null, "适合一起留下记忆点的选择",
                "我会避开成人夜生活，优先找亲子友好、轻松、适合拍照或体验的候选。",
                queries,
                mergeAvoid(preference, extraAvoid),
                weights(baseWeights));
    }

    private SearchPlan generalPlan(String mood, ExperiencePreference preference) {
        List<SearchQuery> queries = new java.util.ArrayList<>();
        if (preference.activityBiases().contains("movie")) {
            queries.add(new SearchQuery("ACTIVITY", "CINEMA", List.of("movie", "indoor")));
            queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", List.of("movie", "indoor")));
        } else {
            queries.add(new SearchQuery("ACTIVITY", "ACTIVITY", preference.activityBiases().isEmpty()
                    ? List.of("indoor", "exhibition", "citywalk")
                    : preference.activityBiases()));
        }
        queries.add(new SearchQuery("DINING", "RESTAURANT", List.of("dessert", "coffee", "social_dining")));

        return new SearchPlan(false, null, "按你的偏好筛过的选择",
                "我会先按你刚才的偏好找一批候选，再让你选要不要加入拼图。",
                queries,
                mergeAvoid(preference, List.of()),
                weights(Map.of("indoor", 14.0, "exhibition", 18.0, "dessert", 18.0, "coffee", 15.0)));
    }

    private String primaryMood(ExperiencePreference preference) {
        if (preference.moods().contains("ritual")) return "ritual";
        if ("budget_friendly".equals(preference.budgetMood()) || preference.moods().contains("budget_friendly")) {
            return "budget_friendly";
        }
        if (preference.moods().contains("topic_safe")) return "topic_safe";
        if ("indoor_first".equals(preference.weatherPolicy())) return "weather_safe";
        return "relaxed";
    }

    private List<String> mergeAvoid(ExperiencePreference preference, List<String> extra) {
        java.util.LinkedHashSet<String> values = new java.util.LinkedHashSet<>(preference.avoid());
        values.addAll(extra);
        values.removeIf(value -> value == null || value.isBlank());
        if ("indoor_first".equals(preference.weatherPolicy()) || values.contains("weather_risk")) {
            values.add("outdoor");
        }
        return List.copyOf(values);
    }

    private Map<String, Double> weights(Map<String, Double> values) {
        return new LinkedHashMap<>(values);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "UNKNOWN" : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    public record SearchPlan(
            boolean needsMoreContext,
            String clarification,
            String title,
            String description,
            List<SearchQuery> queries,
            List<String> avoidTags,
            Map<String, Double> tagWeights
    ) {
        public SearchPlan {
            queries = queries == null ? List.of() : List.copyOf(queries);
            avoidTags = avoidTags == null ? List.of() : List.copyOf(avoidTags);
            tagWeights = tagWeights == null ? Map.of() : Map.copyOf(tagWeights);
        }

        public static SearchPlan needsMoreContext(String clarification) {
            return new SearchPlan(true, clarification, null, null, List.of(), List.of(), Map.of());
        }
    }

    public record SearchQuery(String phase, String category, List<String> tags) {
        public SearchQuery {
            tags = tags == null ? List.of() : List.copyOf(tags);
        }
    }
}
