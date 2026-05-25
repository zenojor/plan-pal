package com.weekendplanner.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weekendplanner.dto.WeatherResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Month;
import java.util.Map;

@Component
public class WeatherTool {

    private static final Logger log = LoggerFactory.getLogger(WeatherTool.class);

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public WeatherTool(@Value("${weather.api-key:}") String apiKey) {
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String getToolName() {
        return "checkWeather";
    }

    public String getDescription() {
        return "查询指定城市和日期的天气预报。参数: location(城市名,如'上海'), date(日期,如'2026-05-25')。返回天气状况、温度、是否适合户外活动及建议。";
    }

    public String execute(String parametersJson) {
        try {
            JsonNode params = objectMapper.readTree(parametersJson);
            String location = params.has("location") ? params.get("location").asText() : "上海";
            String date = params.has("date") ? params.get("date").asText() : LocalDate.now().toString();

            WeatherResponse response;
            if (apiKey != null && !apiKey.isBlank()) {
                response = fetchRealWeather(location, date);
            } else {
                response = fetchMockWeather(location, date);
            }

            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Weather query failed, using mock fallback: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(
                        fetchMockWeather("上海", LocalDate.now().toString()));
            } catch (Exception ex) {
                throw new RuntimeException("Weather query completely failed", ex);
            }
        }
    }

    private WeatherResponse fetchRealWeather(String location, String date) throws Exception {
        String locationId = resolveLocationId(location);
        String url = String.format(
                "https://devapi.qweather.com/v7/weather/3d?location=%s&key=%s", locationId, apiKey);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        if (!"200".equals(root.path("code").asText())) {
            throw new RuntimeException("QWeather API error: " + root.path("code").asText());
        }

        JsonNode daily = root.path("daily");
        String targetDate = date;
        for (JsonNode day : daily) {
            if (targetDate.equals(day.path("fxDate").asText())) {
                String condition = day.path("textDay").asText();
                int tempHigh = day.path("tempMax").asInt();
                int tempLow = day.path("tempMin").asInt();
                String windDir = day.path("windDirDay").asText();
                String windScale = day.path("windScaleDay").asText();
                int windScaleInt = windScale.matches("\\d+") ? Integer.parseInt(windScale)
                        : windScale.matches("\\d+-\\d+") ? Integer.parseInt(windScale.split("-")[1]) : 3;

                return assessWeather(location, date, condition, tempHigh, tempLow, windDir, windScaleInt);
            }
        }
        throw new RuntimeException("Date " + date + " not found in 3-day forecast");
    }

    private String resolveLocationId(String location) throws Exception {
        String url = String.format(
                "https://geoapi.qweather.com/v2/city/lookup?location=%s&key=%s",
                java.net.URLEncoder.encode(location, "UTF-8"), apiKey);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());

        JsonNode locationNode = root.path("location");
        if (locationNode.isArray() && locationNode.size() > 0) {
            return locationNode.get(0).path("id").asText();
        }
        return "101020100";
    }

    private WeatherResponse fetchMockWeather(String location, String date) {
        LocalDate localDate = LocalDate.parse(date);
        Month month = localDate.getMonth();
        int day = localDate.getDayOfMonth();

        String condition;
        int tempHigh;
        int tempLow;
        String windDirection = "东南风";
        int windScale = 2;

        if (month == Month.MAY) {
            condition = "小雨";
            tempHigh = 24;
            tempLow = 17;
        } else if (month == Month.JUNE || month == Month.JULY) {
            condition = "雷阵雨";
            tempHigh = 32;
            tempLow = 25;
        } else if (month == Month.AUGUST) {
            condition = "晴";
            tempHigh = 35;
            tempLow = 27;
        } else if (month == Month.SEPTEMBER || month == Month.OCTOBER) {
            condition = day % 3 == 0 ? "多云" : "晴";
            tempHigh = 28;
            tempLow = 18;
        } else if (month == Month.NOVEMBER || month == Month.MARCH || month == Month.APRIL) {
            condition = day % 2 == 0 ? "多云" : "小雨";
            tempHigh = 18;
            tempLow = 9;
        } else {
            condition = "阴";
            tempHigh = 5;
            tempLow = -2;
        }

        return assessWeather(location, date, condition, tempHigh, tempLow, windDirection, windScale);
    }

    private WeatherResponse assessWeather(String location, String date, String condition,
                                           int tempHigh, int tempLow, String windDirection, int windScale) {
        boolean outdoorFriendly = true;
        String suggestion;

        String cond = condition.toLowerCase();
        if (cond.contains("雨") || cond.contains("雪") || cond.contains("霾") || cond.contains("沙尘")) {
            outdoorFriendly = false;
            suggestion = "可能有" + condition + "，建议带伞，优先室内活动";
        } else if (tempHigh > 35) {
            outdoorFriendly = false;
            suggestion = "高温 " + tempHigh + "°C，注意防暑，建议优先室内活动";
        } else if (tempLow < 5) {
            outdoorFriendly = false;
            suggestion = "低温 " + tempLow + "°C，注意保暖，户外活动需谨慎";
        } else if (windScale >= 6) {
            outdoorFriendly = false;
            suggestion = "风力较大(" + windScale + "级)，户外活动体验不佳，建议室内方案";
        } else if (cond.contains("阴") || cond.contains("多云")) {
            outdoorFriendly = true;
            suggestion = condition + "天气，适合户外活动，建议带薄外套";
        } else {
            outdoorFriendly = true;
            suggestion = "天气晴好，非常适合户外活动";
        }

        return new WeatherResponse(location, date, condition, tempHigh, tempLow,
                windDirection, windScale, outdoorFriendly, suggestion);
    }
}
