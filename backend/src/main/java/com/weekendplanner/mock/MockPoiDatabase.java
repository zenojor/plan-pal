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
        poiDatabase.add(poi("P001", "阳光亲子乐园", "ACTIVITY", 121.4680, 31.2350, 120, "09:00-18:00",
                List.of("child_friendly", "outdoor", "周末特惠")));
        poiDatabase.add(poi("P008", "星海儿童探索馆", "ACTIVITY", 121.4780, 31.2180, 90, "09:00-17:00",
                List.of("child_friendly", "indoor", "science")));
        poiDatabase.add(poi("P009", "欢乐蹦床公园", "ACTIVITY", 121.4450, 31.2250, 60, "09:00-21:00",
                List.of("child_friendly", "indoor", "sports")));

        // P002: 扰动埋点
        poiDatabase.add(poi("P002", "绿意轻食馆", "RESTAURANT", 121.4710, 31.2320, 90, "10:00-21:30",
                List.of("dietary_type=light", "healthy", "organic")));
        poiDatabase.add(poi("P010", "蔬心素食坊", "RESTAURANT", 121.4650, 31.2280, 80, "11:00-21:30",
                List.of("dietary_type=light", "vegan", "quiet")));
        poiDatabase.add(poi("P011", "田园沙拉吧", "RESTAURANT", 121.4800, 31.2400, 70, "09:00-21:00",
                List.of("dietary_type=light", "quick_bite")));

        poiDatabase.add(poi("P003", "城市艺术展览中心", "ACTIVITY", 121.4700, 31.2330, 120, "09:00-17:00",
                List.of("social_entertainment", "exhibition", "indoor")));
        poiDatabase.add(poi("P004", "老街巷 Citywalk 路线", "ACTIVITY", 121.4750, 31.2290, 150, "全天开放",
                List.of("social_entertainment", "citywalk", "outdoor")));
        poiDatabase.add(poi("P012", "沉浸式剧本杀馆", "ACTIVITY", 121.4600, 31.2350, 180, "13:00-23:30",
                List.of("social_entertainment", "indoor", "team")));

        poiDatabase.add(poi("P005", "家味轩中餐厅", "RESTAURANT", 121.4690, 31.2310, 90, "11:00-21:30",
                List.of("chinese", "family_style", "normal")));
        poiDatabase.add(poi("P013", "川湘人家", "RESTAURANT", 121.4580, 31.2220, 80, "11:00-21:30",
                List.of("chinese", "spicy", "normal")));
        poiDatabase.add(poi("P014", "粤味小馆", "RESTAURANT", 121.4500, 31.2400, 70, "11:00-21:30",
                List.of("chinese", "cantonese", "normal")));

        poiDatabase.add(poi("P015", "热辣火锅城", "RESTAURANT", 121.4720, 31.2380, 100, "11:00-23:30",
                List.of("social_dining", "hotpot", "party")));
        poiDatabase.add(poi("P016", "特色小吃街", "RESTAURANT", 121.4770, 31.2270, 60, "10:00-22:00",
                List.of("social_dining", "street_food", "casual")));
        poiDatabase.add(poi("P019", "微醺小酒馆", "RESTAURANT", 121.4760, 31.2240, 90, "18:00-02:00",
                List.of("social_dining", "bar", "cocktail", "nightlife", "casual")));
        poiDatabase.add(poi("P020", "河畔精酿吧", "RESTAURANT", 121.4825, 31.2265, 90, "18:00-02:00",
                List.of("bar", "craft_beer", "drinks", "nightlife")));
        poiDatabase.add(poi("P021", "椒朋友川味烧烤", "RESTAURANT", 121.4748, 31.2288, 60, "18:00-02:00",
                List.of("social_dining", "bbq", "spicy", "late_night", "street_food", "group_friendly")));
        poiDatabase.add(poi("P022", "雾岛安静清吧", "RESTAURANT", 121.4718, 31.2262, 75, "18:00-02:00",
                List.of("bar", "quiet_bar", "cocktail", "wine", "solo_friendly", "drinks")));
        poiDatabase.add(poi("P023", "地下栗子 Club", "RESTAURANT", 121.4860, 31.2295, 90, "21:00-04:00",
                List.of("club", "nightclub", "dance", "late_night", "group_friendly", "drinks")));
        poiDatabase.add(poi("P024", "蓝莓云朵冰沙店", "RESTAURANT", 121.4728, 31.2316, 45, "10:00-22:00",
                List.of("smoothie", "juice", "dessert", "tea", "quick_bite", "solo_friendly")));
        poiDatabase.add(poi("P025", "湘辣小炒铺", "RESTAURANT", 121.4685, 31.2269, 60, "11:00-21:30",
                List.of("spicy", "hunan", "social_dining", "normal", "group_friendly")));
        poiDatabase.add(poi("P026", "红油小龙虾夜宵", "RESTAURANT", 121.4812, 31.2318, 75, "18:00-03:00",
                List.of("spicy", "crayfish", "late_night", "social_dining", "party")));
        poiDatabase.add(poi("P027", "山城九宫格火锅", "RESTAURANT", 121.4795, 31.2355, 90, "11:00-23:30",
                List.of("hotpot", "spicy", "sichuan", "party", "group_friendly")));
        poiDatabase.add(poi("P028", "小橘子果汁咖啡", "RESTAURANT", 121.4698, 31.2337, 45, "08:00-21:00",
                List.of("juice", "coffee", "dessert", "quick_bite", "quiet", "solo_friendly")));
        poiDatabase.add(poi("P029", "月光 Livehouse", "RESTAURANT", 121.4842, 31.2228, 100, "19:00-02:00",
                List.of("livehouse", "bar", "nightlife", "music", "group_friendly", "drinks")));
        poiDatabase.add(poi("P050", "青柠能量碗", "RESTAURANT", 121.4668, 31.2345, 55, "09:00-21:00",
                List.of("dietary_type=light", "healthy", "salad", "quick_bite", "solo_friendly")));
        poiDatabase.add(poi("P051", "松露菌菇意面屋", "RESTAURANT", 121.4778, 31.2362, 80, "11:00-22:00",
                List.of("western", "pasta", "date", "quiet", "normal")));
        poiDatabase.add(poi("P052", "外婆家常菜", "RESTAURANT", 121.4628, 31.2265, 85, "11:00-21:30",
                List.of("chinese", "family_style", "normal", "child_friendly", "group_friendly")));
        poiDatabase.add(poi("P053", "椰香东南亚厨房", "RESTAURANT", 121.4865, 31.2330, 80, "11:00-22:00",
                List.of("southeast_asian", "curry", "normal", "date", "group_friendly")));
        poiDatabase.add(poi("P054", "番茄牛腩小馆", "RESTAURANT", 121.4736, 31.2248, 70, "10:30-22:00",
                List.of("chinese", "comfort_food", "normal", "quick_bite", "family_style")));
        poiDatabase.add(poi("P055", "木炭韩式烤肉", "RESTAURANT", 121.4808, 31.2285, 90, "11:00-00:30",
                List.of("bbq", "korean", "social_dining", "party", "late_night")));
        poiDatabase.add(poi("P056", "白日梦甜品铺", "RESTAURANT", 121.4645, 31.2388, 50, "10:00-22:00",
                List.of("dessert", "tea", "coffee", "date", "photo")));
        poiDatabase.add(poi("P057", "巷口日式拉面", "RESTAURANT", 121.4708, 31.2260, 55, "11:00-23:30",
                List.of("japanese", "ramen", "quick_bite", "solo_friendly", "late_night")));
        poiDatabase.add(poi("P058", "海盐小酒食堂", "RESTAURANT", 121.4598, 31.2328, 85, "17:00-01:30",
                List.of("izakaya", "bar", "social_dining", "drinks", "late_night")));
        poiDatabase.add(poi("P059", "清露茶餐厅", "RESTAURANT", 121.4820, 31.2405, 65, "08:00-21:30",
                List.of("cantonese", "tea", "quick_bite", "normal", "family_style")));
        poiDatabase.add(poi("P060", "暖胃砂锅粥", "RESTAURANT", 121.4752, 31.2222, 70, "17:00-02:00",
                List.of("chinese", "porridge", "late_night", "comfort_food", "quiet")));
        poiDatabase.add(poi("P061", "鲜切寿司吧", "RESTAURANT", 121.4688, 31.2395, 70, "11:00-22:30",
                List.of("japanese", "sushi", "light", "date", "solo_friendly")));
        poiDatabase.add(poi("P062", "野菜蒸汽锅", "RESTAURANT", 121.4892, 31.2310, 85, "11:00-22:00",
                List.of("dietary_type=light", "healthy", "hotpot", "vegetable", "group_friendly")));
        poiDatabase.add(poi("P063", "桂花米酒小馆", "RESTAURANT", 121.4610, 31.2210, 75, "17:00-00:30",
                List.of("chinese", "wine", "quiet_bar", "date", "drinks")));
        poiDatabase.add(poi("P064", "芝士披萨工坊", "RESTAURANT", 121.4850, 31.2378, 75, "10:30-23:00",
                List.of("western", "pizza", "child_friendly", "group_friendly", "casual")));
        poiDatabase.add(poi("P065", "麻酱铜锅涮肉", "RESTAURANT", 121.4572, 31.2358, 95, "11:00-23:30",
                List.of("hotpot", "beijing", "social_dining", "party", "group_friendly")));

        poiDatabase.add(poi("P006", "湖畔城市公园", "ACTIVITY", 121.4800, 31.2320, 60, "全天开放",
                List.of("child_friendly", "outdoor", "free")));
        poiDatabase.add(poi("P007", "星光电影院", "ACTIVITY", 121.4650, 31.2380, 150, "10:00-23:30",
                List.of("social_entertainment", "indoor", "movie")));
        poiDatabase.add(poi("P017", "密室逃脱体验馆", "ACTIVITY", 121.4550, 31.2300, 90, "10:00-22:00",
                List.of("social_entertainment", "indoor", "puzzle", "adult_only")));
        poiDatabase.add(poi("P018", "城市观景台", "ACTIVITY", 121.4820, 31.2200, 45, "10:00-22:00",
                List.of("social_entertainment", "outdoor", "photo")));
        poiDatabase.add(poi("P038", "云朵陶艺工坊", "ACTIVITY", 121.4662, 31.2362, 90, "10:00-21:00",
                List.of("indoor", "craft", "parent_child", "date", "quiet")));
        poiDatabase.add(poi("P039", "自然博物探索站", "ACTIVITY", 121.4816, 31.2390, 120, "09:00-17:30",
                List.of("child_friendly", "indoor", "museum", "science", "educational")));
        poiDatabase.add(poi("P040", "屋顶迷你农场", "ACTIVITY", 121.4692, 31.2208, 75, "10:00-18:30",
                List.of("child_friendly", "outdoor", "nature", "petting_zoo", "photo")));
        poiDatabase.add(poi("P041", "像素游戏厅", "ACTIVITY", 121.4768, 31.2348, 80, "10:00-23:00",
                List.of("indoor", "arcade", "youth", "social_entertainment", "rainy_day")));
        poiDatabase.add(poi("P042", "城市手冲咖啡课", "ACTIVITY", 121.4625, 31.2317, 90, "11:00-20:30",
                List.of("indoor", "workshop", "coffee", "date", "quiet")));
        poiDatabase.add(poi("P043", "滑板口袋公园", "ACTIVITY", 121.4882, 31.2338, 70, "09:00-22:00",
                List.of("outdoor", "sports", "youth", "free", "active")));
        poiDatabase.add(poi("P044", "黑盒小剧场", "ACTIVITY", 121.4588, 31.2372, 110, "14:00-23:00",
                List.of("indoor", "theater", "art_film", "date", "social_entertainment")));
        poiDatabase.add(poi("P045", "城市寻宝定向赛", "ACTIVITY", 121.4732, 31.2243, 120, "09:30-20:00",
                List.of("outdoor", "citywalk", "team", "puzzle", "social_entertainment")));
        poiDatabase.add(poi("P046", "亲子烘焙教室", "ACTIVITY", 121.4642, 31.2215, 100, "10:00-19:30",
                List.of("child_friendly", "indoor", "cooking", "parent_child", "dessert")));
        poiDatabase.add(poi("P047", "霓虹保龄球馆", "ACTIVITY", 121.4848, 31.2385, 90, "10:00-01:00",
                List.of("indoor", "sports", "group_friendly", "nightlife", "social_entertainment")));
        poiDatabase.add(poi("P048", "玻璃花房写真馆", "ACTIVITY", 121.4568, 31.2244, 75, "10:00-21:00",
                List.of("indoor", "photo", "date", "quiet", "family")));
        poiDatabase.add(poi("P049", "滨河骑行驿站", "ACTIVITY", 121.4915, 31.2290, 120, "08:00-20:00",
                List.of("outdoor", "sports", "cycling", "nature", "active")));

        // --- CINEMA (电影院) ---
        poiDatabase.add(poi("P030", "IMAX 国际影城", "CINEMA", 121.4620, 31.2250, 150, "10:00-23:30",
                List.of("social_entertainment", "indoor", "imax", "3d")));
        poiDatabase.add(poi("P031", "周末文艺影院", "CINEMA", 121.4780, 31.2420, 130, "10:00-23:30",
                List.of("social_entertainment", "indoor", "art_film", "couple")));
        poiDatabase.add(poi("P032", "环球巨幕影城", "CINEMA", 121.4830, 31.2360, 150, "10:00-00:30",
                List.of("social_entertainment", "indoor", "giant_screen", "dolby", "late_night")));
        poiDatabase.add(poi("P033", "亲子梦工场影院", "CINEMA", 121.4688, 31.2268, 110, "09:30-22:00",
                List.of("child_friendly", "indoor", "family", "animation")));
        poiDatabase.add(poi("P034", "云端艺术影院", "CINEMA", 121.4595, 31.2335, 120, "11:00-23:30",
                List.of("art_film", "couple", "quiet", "photo")));
        poiDatabase.add(poi("P035", "河畔家庭影院", "CINEMA", 121.4890, 31.2242, 110, "10:00-23:00",
                List.of("child_friendly", "family", "indoor", "easy_parking")));
        poiDatabase.add(poi("P036", "城市中心影院", "CINEMA", 121.4715, 31.2296, 120, "10:00-00:30",
                List.of("central", "indoor", "4k", "late_night")));
        poiDatabase.add(poi("P037", "梧桐小剧场影厅", "CINEMA", 121.4520, 31.2260, 105, "13:00-23:00",
                List.of("art_film", "quiet", "small_theater", "date")));
        poiDatabase.add(poi("P066", "午夜杜比影厅", "CINEMA", 121.4818, 31.2276, 130, "10:00-02:00",
                List.of("social_entertainment", "indoor", "dolby", "late_night", "date")));
        poiDatabase.add(poi("P067", "亲子动画影城", "CINEMA", 121.4638, 31.2402, 105, "09:00-22:00",
                List.of("child_friendly", "family", "animation", "indoor", "easy_parking")));
        poiDatabase.add(poi("P068", "复古胶片影院", "CINEMA", 121.4578, 31.2218, 120, "12:00-23:30",
                List.of("art_film", "retro", "quiet", "couple", "date")));
        poiDatabase.add(poi("P069", "环幕动感影厅", "CINEMA", 121.4875, 31.2350, 140, "10:00-00:30",
                List.of("giant_screen", "4d", "imax", "social_entertainment", "indoor")));

        // --- HOTEL (酒店/民宿) ---
        poiDatabase.add(poi("H001", "城市中心精品酒店", "HOTEL", 121.4700, 31.2350, 720, "全天开放",
                List.of("luxury", "family_suite", "swimming_pool", "breakfast")));
        poiDatabase.add(poi("H002", "人民广场快捷酒店", "HOTEL", 121.4750, 31.2280, 480, "全天开放",
                List.of("budget", "clean", "24h_checkin")));
        poiDatabase.add(poi("H003", "老上海风情民宿", "HOTEL", 121.4550, 31.2180, 600, "全天开放",
                List.of("boutique", "couple", "garden", "photography")));
        poiDatabase.add(poi("H004", "河景家庭公寓", "HOTEL", 121.4862, 31.2215, 720, "全天开放",
                List.of("family_suite", "river_view", "kitchen", "washing_machine")));
        poiDatabase.add(poi("H005", "胶囊青年旅舍", "HOTEL", 121.4672, 31.2248, 480, "全天开放",
                List.of("budget", "solo_friendly", "24h_checkin", "clean")));
        poiDatabase.add(poi("H006", "花园露台精品民宿", "HOTEL", 121.4590, 31.2398, 600, "全天开放",
                List.of("boutique", "garden", "date", "quiet", "breakfast")));
        poiDatabase.add(poi("H007", "亲子主题套房酒店", "HOTEL", 121.4822, 31.2412, 720, "全天开放",
                List.of("child_friendly", "family_suite", "breakfast", "easy_parking")));

        // --- SHOPPING (购物) ---
        poiDatabase.add(poi("S001", "风尚购物中心", "SHOPPING", 121.4680, 31.2320, 120, "10:00-22:00",
                List.of("mall", "fashion", "dining", "cinema")));
        poiDatabase.add(poi("S002", "手作文创集市", "SHOPPING", 121.4720, 31.2220, 90, "10:00-22:00",
                List.of("market", "handicraft", "souvenir", "local")));
        poiDatabase.add(poi("S003", "潮玩集合店", "SHOPPING", 121.4800, 31.2350, 60, "10:00-22:00",
                List.of("trendy", "toys", "collectibles", "youth")));
        poiDatabase.add(poi("S004", "城市书店市集", "SHOPPING", 121.4666, 31.2255, 75, "10:00-22:00",
                List.of("bookstore", "quiet", "souvenir", "indoor")));
        poiDatabase.add(poi("S005", "设计师小物集合店", "SHOPPING", 121.4868, 31.2322, 60, "11:00-21:30",
                List.of("design", "gift", "date", "indoor")));
        poiDatabase.add(poi("S006", "亲子玩具补给站", "SHOPPING", 121.4675, 31.2288, 45, "10:00-21:00",
                List.of("child_friendly", "toys", "quick_stop", "indoor")));
        poiDatabase.add(poi("S007", "中古唱片小店", "SHOPPING", 121.4618, 31.2292, 45, "12:00-22:00",
                List.of("music", "retro", "gift", "date", "indoor")));
        poiDatabase.add(poi("S008", "露天花市", "SHOPPING", 121.4788, 31.2212, 60, "08:00-18:00",
                List.of("market", "flower", "outdoor", "local", "photo")));
        poiDatabase.add(poi("S009", "户外装备集合仓", "SHOPPING", 121.4902, 31.2268, 75, "10:00-21:30",
                List.of("sports", "outdoor", "camping", "cycling", "active")));
        poiDatabase.add(poi("S010", "香氛蜡烛工作室", "SHOPPING", 121.4648, 31.2368, 60, "11:00-21:30",
                List.of("design", "gift", "workshop", "quiet", "date")));
        poiDatabase.add(poi("S011", "儿童绘本书屋", "SHOPPING", 121.4758, 31.2410, 60, "10:00-20:30",
                List.of("child_friendly", "bookstore", "quiet", "indoor", "parent_child")));
        poiDatabase.add(poi("S012", "本地零食补给社", "SHOPPING", 121.4705, 31.2225, 45, "09:00-22:30",
                List.of("snack", "souvenir", "local", "quick_stop", "casual")));
    }

    private PoiDto poi(String id, String name, String category, double lng, double lat, int duration, String businessHours, List<String> tags) {
        return new PoiDto(id, "sandbox", name, category, lng, lat, GeoUtils.distanceKm(HOME_LNG, HOME_LAT, lng, lat), duration, tags, "", "", businessHours, "");
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
