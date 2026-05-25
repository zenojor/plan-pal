package com.weekendplanner.dto;

import java.util.List;

/**
 * 搜索结果
 */
public record SearchResponse(List<PoiDto> results) {}
