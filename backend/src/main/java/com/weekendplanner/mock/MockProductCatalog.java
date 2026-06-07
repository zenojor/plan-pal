package com.weekendplanner.mock;

import com.weekendplanner.dto.ProductItem;
import com.weekendplanner.provider.ProductProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class MockProductCatalog implements ProductProvider {

    private final List<ProductItem> products = new ArrayList<>();

    public MockProductCatalog() {
        add("D001", "多肉葡萄奶盖", "MILK_TEA", "P024", "蓝莓云朵冰沙店", 26, 4.7, 96,
                List.of("奶茶", "葡萄", "奶盖", "甜品", "冰饮"), "果味明显，奶盖厚，适合想喝甜一点但不想太腻的场景。");
        add("D002", "轻乳茉莉奶茶", "MILK_TEA", "P024", "蓝莓云朵冰沙店", 22, 4.6, 91,
                List.of("奶茶", "茉莉", "低糖", "茶香"), "茶香更清爽，适合饭后或散步前顺手带一杯。");
        add("D003", "芒果酸奶冰沙", "SMOOTHIE", "P024", "蓝莓云朵冰沙店", 28, 4.8, 98,
                List.of("冰沙", "酸奶", "芒果", "儿童友好"), "酸甜平衡，停留时间短，适合轻松行程。");
        add("D004", "莓果燕麦杯", "DESSERT", "P024", "蓝莓云朵冰沙店", 24, 4.5, 84,
                List.of("低糖", "健康", "甜品", "轻食"), "低负担甜品，适合描述要求里强调低糖或清爽时推荐。");

        add("D005", "鲜橙柚子气泡", "JUICE", "P028", "小橙子果汁咖啡", 24, 4.5, 88,
                List.of("果汁", "气泡", "清爽", "低负担"), "不太甜，适合想喝清爽饮品的人。");
        add("D006", "桂花拿铁", "COFFEE", "P028", "小橙子果汁咖啡", 29, 4.6, 86,
                List.of("咖啡", "桂花", "热饮", "甜品"), "香气柔和，适合下午轻活动后的休息点。");
        add("D007", "手冲耶加雪菲", "COFFEE", "P028", "小橙子果汁咖啡", 36, 4.8, 92,
                List.of("咖啡", "手冲", "安静", "单人友好"), "适合想安静坐一会儿、聊聊天或等下一场活动。");
        add("D008", "橙香巴斯克", "DESSERT", "P028", "小橙子果汁咖啡", 32, 4.7, 89,
                List.of("甜品", "芝士", "咖啡搭配"), "甜品和咖啡搭配稳定，适合约会或低压聊天。");

        add("F001", "双人轻食沙拉套餐", "SET_MEAL", "P002", "绿意轻食馆", 68, 4.6, 82,
                List.of("轻食", "健康", "低油", "套餐"), "适合不想吃太重口、又需要正餐垫一垫的安排。");
        add("F002", "牛油果鸡胸卷", "LIGHT_MEAL", "P002", "绿意轻食馆", 38, 4.5, 80,
                List.of("轻食", "高蛋白", "quick_bite"), "出餐快，适合紧凑行程。");
        add("F003", "南瓜浓汤", "SOUP", "P002", "绿意轻食馆", 26, 4.4, 76,
                List.of("热食", "温和", "儿童友好"), "温和不刺激，适合雨天或带孩子。");

        add("F004", "炭烤牛肉拼盘", "BBQ", "P021", "椒朋友川味烧烤", 88, 4.7, 94,
                List.of("bbq", "spicy", "group_friendly", "late_night"), "适合朋友局，份量够分享。");
        add("F005", "蒜香烤茄子", "BBQ", "P021", "椒朋友川味烧烤", 28, 4.6, 90,
                List.of("bbq", "素食", "夜宵"), "不太重但有风味，适合搭配主菜。");
        add("F006", "冰粉小碗", "DESSERT", "P021", "椒朋友川味烧烤", 16, 4.5, 87,
                List.of("甜品", "解辣", "低价"), "吃辣后的缓冲选项。");

        add("F007", "湘辣小炒双人餐", "SET_MEAL", "P025", "湘辣小炒铺", 96, 4.6, 88,
                List.of("spicy", "hunan", "social_dining", "套餐"), "适合明确想吃辣、两人或小团体。");
        add("F008", "小炒黄牛肉", "HUNAN", "P025", "湘辣小炒铺", 58, 4.7, 91,
                List.of("spicy", "hunan", "下饭"), "招牌菜，适合正餐。");
        add("F009", "酸梅汤壶", "DRINK", "P025", "湘辣小炒铺", 22, 4.4, 79,
                List.of("饮品", "解辣", "分享"), "辣味餐后更舒服。");
    }

    @Override
    public List<ProductItem> searchProducts(String query, List<String> tags, int limit) {
        String text = ((query == null ? "" : query) + " " + String.join(" ", tags == null ? List.of() : tags))
                .toLowerCase(Locale.ROOT);
        return products.stream()
                .filter(ProductItem::available)
                .filter(product -> matches(product, text, tags))
                .sorted(Comparator.comparingInt(ProductItem::trendingScore).reversed()
                        .thenComparing(Comparator.comparingDouble(ProductItem::rating).reversed()))
                .limit(Math.max(1, limit))
                .toList();
    }

    private void add(String id,
                     String name,
                     String category,
                     String merchantPoiId,
                     String merchantName,
                     double price,
                     double rating,
                     int trendingScore,
                     List<String> tags,
                     String reason) {
        products.add(new ProductItem(id, name, category, merchantPoiId, merchantName, price, rating, trendingScore,
                tags, reason, true));
    }

    private boolean matches(ProductItem product, String text, List<String> tags) {
        String haystack = (product.productName() + " " + product.category() + " " + product.merchantName()
                + " " + String.join(" ", product.tags())).toLowerCase(Locale.ROOT);
        if (tags != null && tags.stream().anyMatch(tag -> haystack.contains(tag.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        if (text.isBlank()) return true;
        if (containsAny(text, "奶茶", "milk tea", "bubble tea")) return containsAny(haystack, "奶茶", "milk_tea");
        if (containsAny(text, "冰沙", "smoothie")) return containsAny(haystack, "冰沙", "smoothie");
        if (containsAny(text, "咖啡", "coffee")) return containsAny(haystack, "咖啡", "coffee");
        if (containsAny(text, "果汁", "juice")) return containsAny(haystack, "果汁", "juice");
        if (containsAny(text, "甜", "dessert")) return containsAny(haystack, "甜品", "dessert");
        if (containsAny(text, "低糖", "healthy", "light")) return containsAny(haystack, "低糖", "健康", "轻食");
        if (containsAny(text, "烧烤", "bbq", "辣", "spicy")) return containsAny(haystack, "bbq", "烧烤", "spicy", "辣");
        return haystack.contains(text) || containsAny(text, "好喝", "饮品", "商品", "吃");
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }
}
