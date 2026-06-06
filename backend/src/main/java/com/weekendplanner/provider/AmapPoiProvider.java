package com.weekendplanner.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.mock.GeoUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@Qualifier("amapPoiProvider")
public class AmapPoiProvider implements PoiProvider {

    private static final String BASE_URL = "https://restapi.amap.com";

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String apiKey;
    private final String city;
    private final double defaultLng;
    private final double defaultLat;
    private final SearchTaxonomyProperties taxonomy;

    public AmapPoiProvider(ObjectMapper objectMapper,
                           SearchTaxonomyProperties taxonomy,
                           @Value("${AMAP_WEB_SERVICE_KEY:}") String apiKey,
                           @Value("${planner.default.city:上海}") String city,
                           @Value("${planner.default.location:121.4737,31.2304}") String defaultLocation) {
        this.restClient = RestClient.builder().baseUrl(BASE_URL).build();
        this.objectMapper = objectMapper;
        this.taxonomy = taxonomy == null ? new SearchTaxonomyProperties() : taxonomy;
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.city = city;
        String[] parts = defaultLocation.split(",");
        this.defaultLng = parts.length > 0 ? Double.parseDouble(parts[0]) : 121.4737;
        this.defaultLat = parts.length > 1 ? Double.parseDouble(parts[1]) : 31.2304;
    }

    @Override
    public List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm) {
        ensureConfigured();
        String url = "/v5/place/around?key=" + apiKey
                + "&location=" + defaultLng + "," + defaultLat
                + "&radius=" + Math.max(1000, radiusKm * 1000)
                + "&sortrule=distance"
                + "&page_size=15"
                + "&types=" + encode(joinTypes(category))
                + "&keywords=" + encode(joinKeywords(category, tags))
                + "&show_fields=business";
        JsonNode root = request(url);
        return mapPois(root.path("pois"), category, defaultLng, defaultLat);
    }

    @Override
    public Optional<PoiDto> findById(String poiId) {
        ensureConfigured();
        String url = "/v5/place/detail?key=" + apiKey
                + "&id=" + encode(poiId)
                + "&show_fields=business";
        JsonNode root = request(url);
        List<PoiDto> pois = mapPois(root.path("pois"), "", defaultLng, defaultLat);
        return pois.stream().findFirst();
    }

    @Override
    public List<PoiDto> searchNearby(String poiId, String category, int radiusKm) {
        PoiDto origin = findById(poiId)
                .orElseThrow(() -> new ProviderIntegrationException("AMap could not resolve origin POI: " + poiId));
        String url = "/v5/place/around?key=" + apiKey
                + "&location=" + origin.lng() + "," + origin.lat()
                + "&radius=" + Math.max(1000, radiusKm * 1000)
                + "&sortrule=distance"
                + "&page_size=15"
                + "&types=" + encode(joinTypes(category))
                + "&keywords=" + encode(joinKeywords(category, List.of()))
                + "&show_fields=business";
        JsonNode root = request(url);
        return mapPois(root.path("pois"), category, origin.lng(), origin.lat()).stream()
                .filter(poi -> !poi.poiId().equalsIgnoreCase(poiId))
                .toList();
    }

    private void ensureConfigured() {
        if (apiKey.isBlank()) {
            throw new ProviderIntegrationException("AMAP_WEB_SERVICE_KEY is not configured");
        }
    }

    private JsonNode request(String url) {
        try {
            String body = restClient.get()
                    .uri(url)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(body);
            String status = root.path("status").asText("");
            if (!"1".equals(status)) {
                throw new ProviderIntegrationException("AMap request failed: " + root.path("info").asText("unknown error"));
            }
            return root;
        } catch (ProviderIntegrationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ProviderIntegrationException("AMap request failed", ex);
        }
    }

    private List<PoiDto> mapPois(JsonNode poisNode, String fallbackCategory, double originLng, double originLat) {
        List<PoiDto> results = new ArrayList<>();
        if (!poisNode.isArray()) {
            return results;
        }
        for (JsonNode poiNode : poisNode) {
            String location = poiNode.path("location").asText("");
            String[] parts = location.split(",");
            if (parts.length < 2) {
                continue;
            }
            double lng = parseDouble(parts[0], originLng);
            double lat = parseDouble(parts[1], originLat);
            String type = poiNode.path("type").asText("");
            String category = normalizeCategory(fallbackCategory, type);
            String businessHours = firstNonBlank(
                    poiNode.at("/business/opentime_today").asText(""),
                    poiNode.at("/business/opentime_week").asText(""),
                    poiNode.at("/business/open_time").asText(""));
            String telephone = firstNonBlank(
                    poiNode.at("/business/tel").asText(""),
                    poiNode.path("tel").asText(""));
            String address = firstNonBlank(poiNode.path("address").asText(""), poiNode.path("pname").asText("")
                    + poiNode.path("cityname").asText("") + poiNode.path("adname").asText(""));
            List<String> tags = tagsFromType(type, category);
            int recommendedDuration = taxonomy.durationFor(category);
            double distance = poiNode.hasNonNull("distance")
                    ? poiNode.path("distance").asDouble() / 1000.0
                    : GeoUtils.distanceKm(originLng, originLat, lng, lat);
            results.add(new PoiDto(
                    poiNode.path("id").asText(""),
                    "amap",
                    poiNode.path("name").asText(""),
                    category,
                    lng,
                    lat,
                    distance,
                    recommendedDuration,
                    tags,
                    address,
                    telephone,
                    businessHours,
                    type
            ));
        }
        return results;
    }

    private String normalizeCategory(String requestedCategory, String type) {
        if (requestedCategory != null && !requestedCategory.isBlank()) {
            String normalized = requestedCategory.toUpperCase(Locale.ROOT);
            if (!"RESTAURANT".equals(normalized)) {
                return normalized;
            }
        }
        String lower = type.toLowerCase(Locale.ROOT);
        if (lower.contains("电影院") || lower.contains("影城")) return "CINEMA";
        if (lower.contains("酒店") || lower.contains("宾馆")) return "HOTEL";
        if (lower.contains("购物") || lower.contains("商场")) return "SHOPPING";
        if (lower.contains("餐饮") || lower.contains("咖啡") || lower.contains("酒吧")) return "RESTAURANT";
        return "ACTIVITY";
    }

    private List<String> tagsFromType(String type, String category) {
        Set<String> tags = new LinkedHashSet<>();
        String lower = type == null ? "" : type.toLowerCase(Locale.ROOT);
        if (category.equals("RESTAURANT")) {
            tags.add("social_dining");
        }
        if (lower.contains("酒吧")) tags.add("bar");
        if (lower.contains("咖啡")) tags.add("coffee");
        if (lower.contains("甜品")) tags.add("dessert");
        if (lower.contains("火锅")) tags.add("hotpot");
        if (lower.contains("烧烤")) tags.add("bbq");
        if (lower.contains("购物")) tags.add("mall");
        if (lower.contains("儿童")) tags.add("child_friendly");
        if (lower.contains("展览") || lower.contains("博物馆")) tags.add("exhibition");
        if (lower.contains("公园")) tags.add("outdoor");
        if (lower.contains("影院")) tags.add("movie");
        if (tags.isEmpty() && !lower.isBlank()) {
            tags.add(lower.replace(";", " "));
        }
        return List.copyOf(tags);
    }

    private String joinTypes(String category) {
        return String.join("|", taxonomy.typeCodesFor(category));
    }

    private String joinKeywords(String category, List<String> tags) {
        if (taxonomy != null) {
            return String.join(" ", taxonomy.keywordsFor(category, tags));
        }
        List<String> terms = new ArrayList<>();
        if (tags != null) {
            terms.addAll(tags);
        }
        if (category != null) {
            switch (category.toUpperCase(Locale.ROOT)) {
                case "ACTIVITY" -> terms.add("展览 博物馆 公园 体验");
                case "DINING", "RESTAURANT" -> terms.add("餐厅 美食");
                case "DRINKS" -> terms.add("酒吧 咖啡");
                case "CINEMA" -> terms.add("电影院");
                case "HOTEL" -> terms.add("酒店");
                case "SHOPPING" -> terms.add("商场 购物");
                default -> {
                }
            }
        }
        return String.join(" ", terms);
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private double parseDouble(String value, double fallback) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return "";
    }

    private String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(firstNonBlank(first, second), third);
    }
}
