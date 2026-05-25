package com.weekendplanner.provider;

import com.weekendplanner.dto.WeatherSnapshot;

import java.time.LocalDate;

public interface WeatherProvider {
    WeatherSnapshot snapshot(String city, LocalDate date, String startTime, String endTime);
}
