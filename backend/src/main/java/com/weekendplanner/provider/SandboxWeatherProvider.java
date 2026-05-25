package com.weekendplanner.provider;

import com.weekendplanner.dto.WeatherSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Component
public class SandboxWeatherProvider implements WeatherProvider {

    private final String defaultRisk;

    @Autowired
    public SandboxWeatherProvider(@Value("${planner.weather.mock.default-risk:AUTO}") String defaultRisk) {
        this.defaultRisk = defaultRisk == null || defaultRisk.isBlank() ? "AUTO" : defaultRisk;
    }

    public SandboxWeatherProvider() {
        this("AUTO");
    }

    @Override
    public WeatherSnapshot snapshot(String city, LocalDate date, String startTime, String endTime) {
        LocalDate targetDate = date == null ? LocalDate.now() : date;
        String targetCity = city == null || city.isBlank() ? "上海" : city;
        String condition = resolveCondition(targetCity, targetDate);
        return switch (condition) {
            case "LIGHT_RAIN" -> new WeatherSnapshot(targetCity, targetDate, condition, 22, 65, 3, "MEDIUM",
                    "小雨，建议优先安排室内或有遮蔽的活动。",
                    List.of("indoor", "sheltered", "mall", "museum", "cafe"),
                    List.of("outdoor", "citywalk"));
            case "HEAVY_RAIN" -> new WeatherSnapshot(targetCity, targetDate, condition, 20, 90, 5, "HIGH",
                    "大雨，户外活动风险较高，建议改成室内项目。",
                    List.of("indoor", "sheltered", "mall", "museum", "cafe"),
                    List.of("outdoor", "citywalk", "sports"));
            case "HOT" -> new WeatherSnapshot(targetCity, targetDate, condition, 35, 20, 2, "MEDIUM",
                    "高温天气，建议减少暴晒和长时间步行。",
                    List.of("indoor", "sheltered", "mall", "cafe"),
                    List.of("outdoor", "citywalk", "sports"));
            case "COLD" -> new WeatherSnapshot(targetCity, targetDate, condition, 4, 15, 4, "MEDIUM",
                    "低温天气，建议选择温暖、停留舒适的场所。",
                    List.of("indoor", "cafe", "museum", "mall"),
                    List.of("outdoor", "citywalk"));
            case "CLOUDY" -> new WeatherSnapshot(targetCity, targetDate, condition, 24, 25, 2, "LOW",
                    "多云，户外活动整体可行。",
                    List.of("outdoor", "citywalk", "indoor"),
                    List.of());
            default -> new WeatherSnapshot(targetCity, targetDate, "CLEAR", 26, 10, 2, "LOW",
                    "天气晴好，户外和室内活动都可安排。",
                    List.of("outdoor", "citywalk", "indoor"),
                    List.of());
        };
    }

    private String resolveCondition(String city, LocalDate date) {
        String forced = defaultRisk.toUpperCase(Locale.ROOT);
        if (!"AUTO".equals(forced)) {
            return switch (forced) {
                case "LOW", "CLEAR" -> "CLEAR";
                case "CLOUDY" -> "CLOUDY";
                case "MEDIUM", "RAIN", "LIGHT_RAIN" -> "LIGHT_RAIN";
                case "HIGH", "HEAVY_RAIN" -> "HEAVY_RAIN";
                case "HOT" -> "HOT";
                case "COLD" -> "COLD";
                default -> "CLEAR";
            };
        }
        int bucket = Math.floorMod(city.hashCode() + date.getDayOfYear(), 6);
        return switch (bucket) {
            case 0 -> "CLEAR";
            case 1 -> "CLOUDY";
            case 2 -> "LIGHT_RAIN";
            case 3 -> "HEAVY_RAIN";
            case 4 -> "HOT";
            default -> "COLD";
        };
    }
}
