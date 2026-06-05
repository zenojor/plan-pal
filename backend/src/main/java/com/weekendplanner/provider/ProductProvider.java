package com.weekendplanner.provider;

import com.weekendplanner.dto.ProductItem;

import java.util.List;

public interface ProductProvider {

    List<ProductItem> searchProducts(String query, List<String> tags, int limit);
}
