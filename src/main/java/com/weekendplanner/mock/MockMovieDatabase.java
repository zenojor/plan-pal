package com.weekendplanner.mock;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Mock 电影排片数据库
 *
 * 提供电影院当日排片信息（片名、场次、时长、价格、评分）。
 */
public class MockMovieDatabase {

    private final List<MovieListing> listings = new ArrayList<>();

    public MockMovieDatabase() { initData(); }

    private void initData() {
        // P019 IMAX 国际影城 排片
        listings.add(movie("M001", "星际探险：新纪元", "科幻/冒险", 135, 9.1,
                "P030", List.of("14:00", "16:30", "19:00", "21:15"), 120.0));
        listings.add(movie("M002", "功夫熊猫5：龙魂觉醒", "动画/喜剧", 105, 8.7,
                "P030", List.of("10:30", "13:00", "15:30", "18:00"), 80.0));
        listings.add(movie("M003", "极速追击", "动作/犯罪", 128, 7.9,
                "P030", List.of("11:00", "14:20", "17:40", "20:30"), 100.0));

        // P020 周末文艺影院 排片
        listings.add(movie("M004", "巴黎最后一夜", "剧情/爱情", 118, 8.9,
                "P031", List.of("14:00", "16:15", "18:45", "21:00"), 70.0));
        listings.add(movie("M005", "独立时代", "文艺/剧情", 145, 9.3,
                "P031", List.of("13:30", "16:00", "19:30"), 65.0));
        listings.add(movie("M006", "城市回声", "纪录/音乐", 98, 8.4,
                "P031", List.of("15:00", "17:30", "20:00"), 60.0));

        // P007 星光电影院 排片
        listings.add(movie("M007", "欢乐喜剧人", "喜剧/家庭", 110, 8.2,
                "P007", List.of("13:30", "16:00", "18:30", "21:00"), 70.0));
        listings.add(movie("M008", "深海奇缘", "动画/冒险", 100, 8.8,
                "P007", List.of("10:00", "12:30", "14:45", "17:00"), 75.0));
        listings.add(movie("M009", "夜巡者", "悬疑/惊悚", 122, 8.0,
                "P007", List.of("14:30", "17:00", "19:30", "22:00"), 85.0));
    }

    private MovieListing movie(String id, String title, String genre, int durationMin,
                               double rating, String cinemaId, List<String> showtimes, double price) {
        return new MovieListing(id, title, genre, durationMin, rating, cinemaId, showtimes, price);
    }

    /**
     * 查询指定电影院的排片
     */
    public List<MovieListing> findByCinema(String cinemaId) {
        return listings.stream()
                .filter(m -> m.cinemaId().equalsIgnoreCase(cinemaId))
                .collect(Collectors.toList());
    }

    /**
     * 根据标签/类型搜索电影
     */
    public List<MovieListing> search(String genre, String keyword) {
        return listings.stream()
                .filter(m -> genre == null || genre.isBlank() || m.genre().contains(genre))
                .filter(m -> keyword == null || keyword.isBlank()
                        || m.title().contains(keyword) || m.genre().contains(keyword))
                .collect(Collectors.toList());
    }

    /**
     * 按电影院 + 时间筛选场次
     */
    public List<MovieListing> searchByCinemaAndTime(String cinemaId, String afterTime) {
        return listings.stream()
                .filter(m -> cinemaId == null || cinemaId.isBlank() || m.cinemaId().equalsIgnoreCase(cinemaId))
                .filter(m -> {
                    if (afterTime == null || afterTime.isBlank()) return true;
                    int after = timeToMinutes(afterTime);
                    return m.showtimes().stream().anyMatch(t -> timeToMinutes(t) >= after);
                })
                .collect(Collectors.toList());
    }

    private int timeToMinutes(String time) {
        String[] parts = time.split(":");
        return Integer.parseInt(parts[0]) * 60 + Integer.parseInt(parts[1]);
    }

    public record MovieListing(
            String movieId,
            String title,
            String genre,
            int durationMinutes,
            double rating,
            String cinemaId,
            List<String> showtimes,
            double pricePerTicket
    ) {}
}
