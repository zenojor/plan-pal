package com.weekendplanner.mock;

import com.weekendplanner.dto.PoiDto;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mock POI 数据库 - 模拟真实的 POI 数据源
 *
 * 关键设计: P002 (轻食餐厅) 埋有扰动逻辑，查询时返回高延迟排队状态，
 * 用于检验 Agent 在异常场景下的重规划能力。
 */
@Component
public class MockPoiDatabase {

    private final List<PoiDto> poiDatabase = new ArrayList<>();

    public MockPoiDatabase() {
        initData();
    }

    private void initData() {
        // ========== 亲子乐园 / 儿童友好 ==========
        poiDatabase.add(new PoiDto("P001", "阳光亲子乐园", "ACTIVITY", 1.2, 120,
                List.of("child_friendly", "outdoor", "周末特惠")));
        poiDatabase.add(new PoiDto("P008", "星海儿童探索馆", "ACTIVITY", 2.8, 90,
                List.of("child_friendly", "indoor", "science")));
        poiDatabase.add(new PoiDto("P009", "欢乐蹦床公园", "ACTIVITY", 4.2, 60,
                List.of("child_friendly", "indoor", "sports")));

        // ========== 轻食/健康餐厅 ==========
        // P002: 关键扰动点 - 设计文档中指定的触发重规划的埋点
        poiDatabase.add(new PoiDto("P002", "绿意轻食馆", "RESTAURANT", 0.8, 90,
                List.of("dietary_type=light", "healthy", "organic")));
        poiDatabase.add(new PoiDto("P010", "蔬心素食坊", "RESTAURANT", 1.5, 80,
                List.of("dietary_type=light", "vegan", "quiet")));
        poiDatabase.add(new PoiDto("P011", "田园沙拉吧", "RESTAURANT", 2.3, 70,
                List.of("dietary_type=light", "quick_bite")));

        // ========== 朋友聚会 / 社交类 ==========
        poiDatabase.add(new PoiDto("P003", "城市艺术展览中心", "ACTIVITY", 1.8, 120,
                List.of("social_entertainment", "exhibition", "indoor")));
        poiDatabase.add(new PoiDto("P004", "老街巷 Citywalk 路线", "ACTIVITY", 0.5, 150,
                List.of("social_entertainment", "citywalk", "outdoor")));
        poiDatabase.add(new PoiDto("P012", "沉浸式剧本杀馆", "ACTIVITY", 2.5, 180,
                List.of("social_entertainment", "indoor", "team")));

        // ========== 普通中餐厅 (降级备选) ==========
        poiDatabase.add(new PoiDto("P005", "家味轩中餐厅", "RESTAURANT", 1.3, 90,
                List.of("chinese", "family_style", "normal")));
        poiDatabase.add(new PoiDto("P013", "川湘人家", "RESTAURANT", 3.2, 80,
                List.of("chinese", "spicy", "normal")));
        poiDatabase.add(new PoiDto("P014", "粤味小馆", "RESTAURANT", 4.5, 70,
                List.of("chinese", "cantonese", "normal")));

        // ========== 社交餐饮 ==========
        poiDatabase.add(new PoiDto("P015", "热辣火锅城", "RESTAURANT", 2.0, 100,
                List.of("social_dining", "hotpot", "party")));
        poiDatabase.add(new PoiDto("P016", "特色小吃街", "RESTAURANT", 1.0, 60,
                List.of("social_dining", "street_food", "casual")));

        // ========== 额外活动场所 ==========
        poiDatabase.add(new PoiDto("P006", "湖畔城市公园", "ACTIVITY", 0.6, 60,
                List.of("child_friendly", "outdoor", "free")));
        poiDatabase.add(new PoiDto("P007", "星光电影院", "ACTIVITY", 2.1, 150,
                List.of("social_entertainment", "indoor", "movie")));
        poiDatabase.add(new PoiDto("P017", "密室逃脱体验馆", "ACTIVITY", 2.8, 90,
                List.of("social_entertainment", "indoor", "puzzle", "adult_only")));
        poiDatabase.add(new PoiDto("P018", "城市观景台", "ACTIVITY", 3.5, 45,
                List.of("social_entertainment", "outdoor", "photo")));
    }

    /**
     * 根据类别和标签搜索 POI
     */
    public List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm) {
        return poiDatabase.stream()
                .filter(p -> category == null || category.isBlank() || p.category().equalsIgnoreCase(category))
                .filter(p -> p.distanceKm() <= radiusKm)
                .filter(p -> tags == null || tags.isEmpty() || matchesAnyTag(p, tags))
                .sorted(Comparator.comparingDouble(PoiDto::distanceKm))
                .collect(Collectors.toList());
    }

    /**
     * 根据 ID 查找 POI
     */
    public Optional<PoiDto> findById(String poiId) {
        return poiDatabase.stream()
                .filter(p -> p.poiId().equals(poiId))
                .findFirst();
    }

    /**
     * 在指定 POI 周围搜索(排除自身)
     */
    public List<PoiDto> searchNearby(String poiId, String category, int radiusKm) {
        PoiDto origin = findById(poiId).orElse(null);
        if (origin == null) {
            return List.of();
        }
        return poiDatabase.stream()
                .filter(p -> !p.poiId().equals(poiId))
                .filter(p -> category == null || p.category().equalsIgnoreCase(category))
                .filter(p -> Math.abs(p.distanceKm() - origin.distanceKm()) <= radiusKm)
                .sorted(Comparator.comparingDouble(PoiDto::distanceKm))
                .collect(Collectors.toList());
    }

    private boolean matchesAnyTag(PoiDto poi, List<String> tags) {
        return tags.stream().anyMatch(tag ->
                poi.tags().stream().anyMatch(poiTag ->
                        poiTag.equalsIgnoreCase(tag) || poiTag.toLowerCase().contains(tag.toLowerCase())));
    }
}
