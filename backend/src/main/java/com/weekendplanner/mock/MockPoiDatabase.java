package com.weekendplanner.mock;

import com.weekendplanner.dto.PoiDto;
import com.weekendplanner.provider.PoiProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mock POI 数据库 — 上海人民广场周边坐标
 *
 * 家里位置: 人民广场 (121.4737, 31.2304)
 * P002 为扰动埋点: checkAvailability 固定返回 90min 排队
 */
@Component
public class MockPoiDatabase implements PoiProvider {

    public static final double HOME_LNG = 121.4737;
    public static final double HOME_LAT = 31.2304;

    private final List<PoiDto> poiDatabase = new ArrayList<>();

    public MockPoiDatabase() { initData(); }

    private void initData() {
        poiDatabase.add(poi("P001", "阳光亲子乐园", "ACTIVITY", 121.4680, 31.2350, 120,
                List.of("child_friendly", "outdoor", "周末特惠")));
        poiDatabase.add(poi("P008", "星海儿童探索馆", "ACTIVITY", 121.4780, 31.2180, 90,
                List.of("child_friendly", "indoor", "science")));
        poiDatabase.add(poi("P009", "欢乐蹦床公园", "ACTIVITY", 121.4450, 31.2250, 60,
                List.of("child_friendly", "indoor", "sports")));

        // P002: 扰动埋点
        poiDatabase.add(poi("P002", "绿意轻食馆", "RESTAURANT", 121.4710, 31.2320, 90,
                List.of("dietary_type=light", "healthy", "organic")));
        poiDatabase.add(poi("P010", "蔬心素食坊", "RESTAURANT", 121.4650, 31.2280, 80,
                List.of("dietary_type=light", "vegan", "quiet")));
        poiDatabase.add(poi("P011", "田园沙拉吧", "RESTAURANT", 121.4800, 31.2400, 70,
                List.of("dietary_type=light", "quick_bite")));

        poiDatabase.add(poi("P003", "城市艺术展览中心", "ACTIVITY", 121.4700, 31.2330, 120,
                List.of("social_entertainment", "exhibition", "indoor")));
        poiDatabase.add(poi("P004", "老街巷 Citywalk 路线", "ACTIVITY", 121.4750, 31.2290, 150,
                List.of("social_entertainment", "citywalk", "outdoor")));
        poiDatabase.add(poi("P012", "沉浸式剧本杀馆", "ACTIVITY", 121.4600, 31.2350, 180,
                List.of("social_entertainment", "indoor", "team")));

        poiDatabase.add(poi("P005", "家味轩中餐厅", "RESTAURANT", 121.4690, 31.2310, 90,
                List.of("chinese", "family_style", "normal")));
        poiDatabase.add(poi("P013", "川湘人家", "RESTAURANT", 121.4580, 31.2220, 80,
                List.of("chinese", "spicy", "normal")));
        poiDatabase.add(poi("P014", "粤味小馆", "RESTAURANT", 121.4500, 31.2400, 70,
                List.of("chinese", "cantonese", "normal")));

        poiDatabase.add(poi("P015", "热辣火锅城", "RESTAURANT", 121.4720, 31.2380, 100,
                List.of("social_dining", "hotpot", "party")));
        poiDatabase.add(poi("P016", "特色小吃街", "RESTAURANT", 121.4770, 31.2270, 60,
                List.of("social_dining", "street_food", "casual")));
        poiDatabase.add(poi("P019", "微醺小酒馆", "RESTAURANT", 121.4760, 31.2240, 90,
                List.of("social_dining", "bar", "cocktail", "nightlife", "casual")));
        poiDatabase.add(poi("P020", "河畔精酿吧", "RESTAURANT", 121.4825, 31.2265, 90,
                List.of("bar", "craft_beer", "drinks", "nightlife")));
        poiDatabase.add(poi("P021", "椒朋友川味烧烤", "RESTAURANT", 121.4748, 31.2288, 60,
                List.of("social_dining", "bbq", "spicy", "late_night", "street_food", "group_friendly")));
        poiDatabase.add(poi("P022", "雾岛安静清吧", "RESTAURANT", 121.4718, 31.2262, 75,
                List.of("bar", "quiet_bar", "cocktail", "wine", "solo_friendly", "drinks")));
        poiDatabase.add(poi("P023", "地下栗子 Club", "RESTAURANT", 121.4860, 31.2295, 90,
                List.of("club", "nightclub", "dance", "late_night", "group_friendly", "drinks")));
        poiDatabase.add(poi("P024", "蓝莓云朵冰沙店", "RESTAURANT", 121.4728, 31.2316, 45,
                List.of("smoothie", "juice", "dessert", "tea", "quick_bite", "solo_friendly")));
        poiDatabase.add(poi("P025", "湘辣小炒铺", "RESTAURANT", 121.4685, 31.2269, 60,
                List.of("spicy", "hunan", "social_dining", "normal", "group_friendly")));
        poiDatabase.add(poi("P026", "红油小龙虾夜宵", "RESTAURANT", 121.4812, 31.2318, 75,
                List.of("spicy", "crayfish", "late_night", "social_dining", "party")));
        poiDatabase.add(poi("P027", "山城九宫格火锅", "RESTAURANT", 121.4795, 31.2355, 90,
                List.of("hotpot", "spicy", "sichuan", "party", "group_friendly")));
        poiDatabase.add(poi("P028", "小橘子果汁咖啡", "RESTAURANT", 121.4698, 31.2337, 45,
                List.of("juice", "coffee", "dessert", "quick_bite", "quiet", "solo_friendly")));
        poiDatabase.add(poi("P029", "月光 Livehouse", "RESTAURANT", 121.4842, 31.2228, 100,
                List.of("livehouse", "bar", "nightlife", "music", "group_friendly", "drinks")));

        poiDatabase.add(poi("P006", "湖畔城市公园", "ACTIVITY", 121.4800, 31.2320, 60,
                List.of("child_friendly", "outdoor", "free")));
        poiDatabase.add(poi("P007", "星光电影院", "ACTIVITY", 121.4650, 31.2380, 150,
                List.of("social_entertainment", "indoor", "movie")));
        poiDatabase.add(poi("P017", "密室逃脱体验馆", "ACTIVITY", 121.4550, 31.2300, 90,
                List.of("social_entertainment", "indoor", "puzzle", "adult_only")));
        poiDatabase.add(poi("P018", "城市观景台", "ACTIVITY", 121.4820, 31.2200, 45,
                List.of("social_entertainment", "outdoor", "photo")));

        // --- CINEMA (电影院) ---
        poiDatabase.add(poi("P030", "IMAX 国际影城", "CINEMA", 121.4620, 31.2250, 150,
                List.of("social_entertainment", "indoor", "imax", "3d")));
        poiDatabase.add(poi("P031", "周末文艺影院", "CINEMA", 121.4780, 31.2420, 130,
                List.of("social_entertainment", "indoor", "art_film", "couple")));

        // --- HOTEL (酒店/民宿) ---
        poiDatabase.add(poi("H001", "城市中心精品酒店", "HOTEL", 121.4700, 31.2350, 720,
                List.of("luxury", "family_suite", "swimming_pool", "breakfast")));
        poiDatabase.add(poi("H002", "人民广场快捷酒店", "HOTEL", 121.4750, 31.2280, 480,
                List.of("budget", "clean", "24h_checkin")));
        poiDatabase.add(poi("H003", "老上海风情民宿", "HOTEL", 121.4550, 31.2180, 600,
                List.of("boutique", "couple", "garden", "photography")));

        // --- SHOPPING (购物) ---
        poiDatabase.add(poi("S001", "风尚购物中心", "SHOPPING", 121.4680, 31.2320, 120,
                List.of("mall", "fashion", "dining", "cinema")));
        poiDatabase.add(poi("S002", "手作文创集市", "SHOPPING", 121.4720, 31.2220, 90,
                List.of("market", "handicraft", "souvenir", "local")));
        poiDatabase.add(poi("S003", "潮玩集合店", "SHOPPING", 121.4800, 31.2350, 60,
                List.of("trendy", "toys", "collectibles", "youth")));
    }

    private PoiDto poi(String id, String name, String category, double lng, double lat, int duration, List<String> tags) {
        return new PoiDto(id, name, category, lng, lat, GeoUtils.distanceKm(HOME_LNG, HOME_LAT, lng, lat), duration, tags);
    }

    public List<PoiDto> searchByCategory(String category, List<String> tags, int radiusKm) {
        return poiDatabase.stream()
                .filter(p -> category == null || category.isBlank() || p.category().equalsIgnoreCase(category))
                .filter(p -> p.distanceKm() <= radiusKm)
                .filter(p -> tags == null || tags.isEmpty() || matchesAnyTag(p, tags))
                .sorted(Comparator.comparingDouble(PoiDto::distanceKm))
                .collect(Collectors.toList());
    }

    public Optional<PoiDto> findById(String poiId) {
        return poiDatabase.stream().filter(p -> p.poiId().equals(poiId)).findFirst();
    }

    public List<PoiDto> searchNearby(String poiId, String category, int radiusKm) {
        PoiDto origin = findById(poiId).orElse(null);
        if (origin == null) return List.of();
        return poiDatabase.stream()
                .filter(p -> !p.poiId().equals(poiId))
                .filter(p -> category == null || p.category().equalsIgnoreCase(category))
                .filter(p -> GeoUtils.distanceKm(origin.lng(), origin.lat(), p.lng(), p.lat()) <= radiusKm)
                .sorted(Comparator.comparingDouble(p -> GeoUtils.distanceKm(origin.lng(), origin.lat(), p.lng(), p.lat())))
                .collect(Collectors.toList());
    }

    private boolean matchesAnyTag(PoiDto poi, List<String> tags) {
        return tags.stream().anyMatch(tag ->
                poi.tags().stream().anyMatch(poiTag ->
                        poiTag.equalsIgnoreCase(tag) || poiTag.toLowerCase().contains(tag.toLowerCase())));
    }
}
