package com.weekendplanner.provider;

import com.weekendplanner.dto.WeatherSnapshot;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxWeatherProviderTest {

    @Test
    void sameCityAndDateReturnStableWeather() {
        SandboxWeatherProvider provider = new SandboxWeatherProvider();
        LocalDate date = LocalDate.of(2026, 5, 25);

        WeatherSnapshot first = provider.snapshot("上海", date, "14:00", "18:00");
        WeatherSnapshot second = provider.snapshot("上海", date, "14:00", "18:00");

        assertThat(second).isEqualTo(first);
    }

    @Test
    void nearbyDatesCoverDifferentMockBuckets() {
        SandboxWeatherProvider provider = new SandboxWeatherProvider();
        Set<String> conditions = IntStream.range(0, 6)
                .mapToObj(offset -> provider.snapshot("上海", LocalDate.of(2026, 5, 25).plusDays(offset), "14:00", "18:00"))
                .map(WeatherSnapshot::condition)
                .collect(Collectors.toSet());

        assertThat(conditions).hasSizeGreaterThan(1);
    }

    @Test
    void forcedHighRiskCreatesHeavyRainIndoorPreference() {
        SandboxWeatherProvider provider = new SandboxWeatherProvider("HIGH");

        WeatherSnapshot weather = provider.snapshot("上海", LocalDate.of(2026, 5, 25), "14:00", "18:00");

        assertThat(weather.condition()).isEqualTo("HEAVY_RAIN");
        assertThat(weather.outdoorRiskLevel()).isEqualTo("HIGH");
        assertThat(weather.preferredTags()).contains("indoor");
        assertThat(weather.avoidTags()).contains("outdoor");
    }
}
