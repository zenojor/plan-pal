package com.weekendplanner.dto;

import java.util.List;

/**
 * 搜索请求 - LocationExplorationTool 入参
 */
public record SearchRequest(
        String category,
        List<String> tags,
        int radiusKm
) {}
