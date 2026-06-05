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
        products.add(product("D001", "多肉葡萄奶盖", "MILK_TEA", "P024", "蓝莓云朵冰沙店",
                26, 4.7, 96, List.of("奶茶", "葡萄", "奶盖", "甜品", "冰饮"),
                "果味明显，奶盖厚，适合想喝甜一点但不想太腻的场景。"));
        products.add(product("D002", "轻乳茉莉奶茶", "MILK_TEA", "P024", "蓝莓云朵冰沙店",
                22, 4.6, 91, List.of("奶茶", "茉莉", "低糖", "茶香"),
                "茶香更清爽，适合饭后或散步前顺手带一杯。"));
        products.add(product("D003", "芒果酸奶冰沙", "SMOOTHIE", "P024", "蓝莓云朵冰沙店",
                28, 4.8, 98, List.of("冰沙", "酸奶", "芒果", "儿童友好"),
                "酸甜平衡，停留时间短，适合亲子轻松行程。"));
        products.add(product("D004", "鲜榨橙柚气泡", "JUICE", "P028", "小橙子果汁咖啡",
                24, 4.5, 88, List.of("果汁", "气泡", "清爽", "低负担"),
                "不太甜，适合想喝清爽饮品的人。"));
        products.add(product("D005", "桂花拿铁", "COFFEE", "P028", "小橙子果汁咖啡",
                29, 4.6, 86, List.of("咖啡", "桂花", "热饮", "甜品"),
                "香气柔和，适合下午轻活动后的休息点。"));
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

    private ProductItem product(String id,
                                String name,
                                String category,
                                String merchantPoiId,
                                String merchantName,
                                double price,
                                double rating,
                                int trendingScore,
                                List<String> tags,
                                String reason) {
        return new ProductItem(id, name, category, merchantPoiId, merchantName, price, rating, trendingScore,
                tags, reason, true);
    }

    private boolean matches(ProductItem product, String text, List<String> tags) {
        String haystack = (product.productName() + " " + product.category() + " " + product.merchantName()
                + " " + String.join(" ", product.tags())).toLowerCase(Locale.ROOT);
        if (tags != null && tags.stream().anyMatch(tag -> haystack.contains(tag.toLowerCase(Locale.ROOT)))) {
            return true;
        }
        if (text.isBlank()) return true;
        if (text.contains("奶茶") || text.contains("milk tea") || text.contains("bubble tea")) {
            return haystack.contains("奶茶") || haystack.contains("milk_tea");
        }
        if (text.contains("冰沙") || text.contains("smoothie")) return haystack.contains("冰沙") || haystack.contains("smoothie");
        if (text.contains("咖啡") || text.contains("coffee")) return haystack.contains("咖啡") || haystack.contains("coffee");
        if (text.contains("果汁") || text.contains("juice")) return haystack.contains("果汁") || haystack.contains("juice");
        return haystack.contains(text) || text.contains("甜") || text.contains("好喝") || text.contains("饮品");
    }
}
