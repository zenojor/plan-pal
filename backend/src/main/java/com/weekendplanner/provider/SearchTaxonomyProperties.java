package com.weekendplanner.provider;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConfigurationProperties(prefix = "search.taxonomy")
public class SearchTaxonomyProperties {

    private Map<String, List<String>> amapTypeCodes = defaultTypeCodes();
    private Map<String, List<String>> keywords = defaultKeywords();
    private Map<String, Integer> durations = defaultDurations();

    public List<String> typeCodesFor(String category) {
        return amapTypeCodes.getOrDefault(normalize(category), List.of("050000"));
    }

    public List<String> keywordsFor(String category, List<String> tags) {
        List<String> terms = new ArrayList<>();
        if (tags != null) terms.addAll(tags);
        terms.addAll(keywords.getOrDefault(normalize(category), List.of()));
        return terms;
    }

    public int durationFor(String category) {
        return durations.getOrDefault(normalize(category), 90);
    }

    public Map<String, List<String>> getAmapTypeCodes() {
        return amapTypeCodes;
    }

    public void setAmapTypeCodes(Map<String, List<String>> amapTypeCodes) {
        this.amapTypeCodes = normalizeMap(amapTypeCodes, defaultTypeCodes());
    }

    public Map<String, List<String>> getKeywords() {
        return keywords;
    }

    public void setKeywords(Map<String, List<String>> keywords) {
        this.keywords = normalizeMap(keywords, defaultKeywords());
    }

    public Map<String, Integer> getDurations() {
        return durations;
    }

    public void setDurations(Map<String, Integer> durations) {
        this.durations = durations == null || durations.isEmpty() ? defaultDurations() : durations;
    }

    private static Map<String, List<String>> defaultTypeCodes() {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("ACTIVITY", List.of("050000", "060000", "110000", "141200"));
        mapping.put("DINING", List.of("050100", "050200", "050300"));
        mapping.put("DRINKS", List.of("050400", "050500", "050600"));
        mapping.put("RESTAURANT", List.of("050000"));
        mapping.put("CINEMA", List.of("080601"));
        mapping.put("HOTEL", List.of("100000"));
        mapping.put("SHOPPING", List.of("060000"));
        return mapping;
    }

    private static Map<String, List<String>> defaultKeywords() {
        Map<String, List<String>> mapping = new LinkedHashMap<>();
        mapping.put("ACTIVITY", List.of("exhibition", "museum", "park", "experience"));
        mapping.put("DINING", List.of("restaurant", "food"));
        mapping.put("RESTAURANT", List.of("restaurant", "food"));
        mapping.put("DRINKS", List.of("bar", "coffee"));
        mapping.put("CINEMA", List.of("cinema"));
        mapping.put("HOTEL", List.of("hotel"));
        mapping.put("SHOPPING", List.of("shopping", "mall"));
        return mapping;
    }

    private static Map<String, Integer> defaultDurations() {
        Map<String, Integer> mapping = new LinkedHashMap<>();
        mapping.put("DINING", 75);
        mapping.put("RESTAURANT", 75);
        mapping.put("DRINKS", 75);
        mapping.put("CINEMA", 150);
        mapping.put("HOTEL", 480);
        mapping.put("SHOPPING", 120);
        mapping.put("ACTIVITY", 90);
        return mapping;
    }

    private Map<String, List<String>> normalizeMap(Map<String, List<String>> input, Map<String, List<String>> fallback) {
        if (input == null || input.isEmpty()) return fallback;
        Map<String, List<String>> normalized = new LinkedHashMap<>();
        input.forEach((key, value) -> normalized.put(normalize(key), value == null ? List.of() : List.copyOf(value)));
        return normalized;
    }

    private String normalize(String category) {
        return category == null ? "" : category.toUpperCase(Locale.ROOT);
    }
}
