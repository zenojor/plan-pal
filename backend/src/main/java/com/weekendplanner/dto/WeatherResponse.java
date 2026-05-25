package com.weekendplanner.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record WeatherResponse(
        String location,
        String date,
        String condition,
        @JsonProperty("temp_high") int tempHigh,
        @JsonProperty("temp_low") int tempLow,
        @JsonProperty("wind_direction") String windDirection,
        @JsonProperty("wind_scale") int windScale,
        @JsonProperty("outdoor_friendly") boolean outdoorFriendly,
        String suggestion) {
}
