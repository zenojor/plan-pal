package com.weekendplanner.mock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Sandbox movie listing database.
 *
 * The data is intentionally layered as movie -> cinema -> screening so the UI can
 * show a real screening choice instead of inventing a cinema/time later.
 */
public class MockMovieDatabase {

    private final List<MovieListing> listings = new ArrayList<>();

    public MockMovieDatabase() {
        initData();
    }

    private void initData() {
        addMovie("M001", "星际远航：新纪元", "科幻/冒险", 135, 9.1, List.of(
                screening("S-M001-P030-1400", "P030", "IMAX 国际影城", "14:00", "16:15", "1号IMAX厅", "IMAX 3D", "原版中字", 120, 48),
                screening("S-M001-P031-1530", "P031", "周末文艺影院", "15:30", "17:45", "2号厅", "CINITY", "原版中字", 88, 36),
                screening("S-M001-P032-1900", "P032", "环球巨幕影城", "19:00", "21:15", "巨幕厅", "杜比全景声", "原版中字", 108, 64),
                screening("S-M001-P034-2110", "P034", "云端艺术影院", "21:10", "23:25", "星空厅", "Laser", "原版中字", 96, 22)
        ));
        addMovie("M002", "熊猫快递员", "动画/喜剧", 105, 8.7, List.of(
                screening("S-M002-P007-1030", "P007", "星光电影院", "10:30", "12:15", "亲子厅", "2D", "国语", 68, 72),
                screening("S-M002-P030-1300", "P030", "IMAX 国际影城", "13:00", "14:45", "5号厅", "3D", "国语", 80, 55),
                screening("S-M002-P033-1540", "P033", "亲子梦工场影院", "15:40", "17:25", "童趣厅", "2D", "国语", 58, 40),
                screening("S-M002-P035-1830", "P035", "河畔家庭影院", "18:30", "20:15", "家庭厅", "2D", "国语", 62, 30)
        ));
        addMovie("M003", "极速追击", "动作/犯罪", 128, 7.9, List.of(
                screening("S-M003-P030-1120", "P030", "IMAX 国际影城", "11:20", "13:28", "3号厅", "IMAX 2D", "原版中字", 100, 41),
                screening("S-M003-P032-1640", "P032", "环球巨幕影城", "16:40", "18:48", "激光厅", "Laser", "原版中字", 92, 52),
                screening("S-M003-P036-2030", "P036", "城市中心影院", "20:30", "22:38", "6号厅", "4K", "原版中字", 76, 66)
        ));
        addMovie("M004", "巴黎最后一日", "剧情/爱情", 118, 8.9, List.of(
                screening("S-M004-P031-1410", "P031", "周末文艺影院", "14:10", "16:08", "文艺厅", "2D", "原版中字", 70, 28),
                screening("S-M004-P034-1845", "P034", "云端艺术影院", "18:45", "20:43", "露台厅", "Laser", "原版中字", 78, 34),
                screening("S-M004-P037-2100", "P037", "梧桐小剧场影厅", "21:00", "22:58", "小剧场", "2D", "原版中字", 65, 18)
        ));
        addMovie("M005", "独立时代", "文艺/剧情", 145, 9.3, List.of(
                screening("S-M005-P031-1330", "P031", "周末文艺影院", "13:30", "15:55", "文艺厅", "2D", "国语", 65, 24),
                screening("S-M005-P034-1600", "P034", "云端艺术影院", "16:00", "18:25", "星空厅", "2D", "国语", 72, 31),
                screening("S-M005-P037-1930", "P037", "梧桐小剧场影厅", "19:30", "21:55", "小剧场", "2D", "国语", 60, 16)
        ));
        addMovie("M006", "城市回声", "纪录/音乐", 98, 8.4, List.of(
                screening("S-M006-P031-1500", "P031", "周末文艺影院", "15:00", "16:38", "3号厅", "2D", "国语", 60, 45),
                screening("S-M006-P034-1730", "P034", "云端艺术影院", "17:30", "19:08", "星空厅", "Laser", "国语", 68, 39),
                screening("S-M006-P036-2000", "P036", "城市中心影院", "20:00", "21:38", "5号厅", "4K", "国语", 66, 58)
        ));
        addMovie("M007", "欢乐喜剧人", "喜剧/家庭", 110, 8.2, List.of(
                screening("S-M007-P007-1330", "P007", "星光电影院", "13:30", "15:20", "4号厅", "2D", "国语", 70, 62),
                screening("S-M007-P033-1600", "P033", "亲子梦工场影院", "16:00", "17:50", "童趣厅", "2D", "国语", 58, 43),
                screening("S-M007-P035-1830", "P035", "河畔家庭影院", "18:30", "20:20", "家庭厅", "2D", "国语", 64, 29)
        ));
        addMovie("M008", "深海奇缘", "动画/冒险", 100, 8.8, List.of(
                screening("S-M008-P007-1000", "P007", "星光电影院", "10:00", "11:40", "亲子厅", "3D", "国语", 75, 70),
                screening("S-M008-P030-1445", "P030", "IMAX 国际影城", "14:45", "16:25", "2号厅", "IMAX 3D", "国语", 92, 44),
                screening("S-M008-P033-1700", "P033", "亲子梦工场影院", "17:00", "18:40", "童趣厅", "2D", "国语", 60, 35)
        ));
        addMovie("M009", "夜巡者", "悬疑/惊悚", 122, 8.0, List.of(
                screening("S-M009-P007-1930", "P007", "星光电影院", "19:30", "21:32", "6号厅", "2D", "国语", 85, 53),
                screening("S-M009-P032-2200", "P032", "环球巨幕影城", "22:00", "00:02", "激光厅", "Laser", "国语", 88, 47),
                screening("S-M009-P036-2240", "P036", "城市中心影院", "22:40", "00:42", "7号厅", "4K", "国语", 78, 39)
        ));
    }

    private void addMovie(String movieId, String title, String genre, int durationMin,
                          double rating, List<Screening> screenings) {
        Map<String, List<Screening>> byCinema = new LinkedHashMap<>();
        for (Screening screening : expandScreenings(screenings, durationMin)) {
            byCinema.computeIfAbsent(screening.cinemaId(), ignored -> new ArrayList<>())
                    .add(screening.withMovie(movieId, title));
        }
        for (Map.Entry<String, List<Screening>> entry : byCinema.entrySet()) {
            List<Screening> cinemaScreenings = entry.getValue().stream()
                    .sorted(Comparator.comparing(Screening::startTime))
                    .toList();
            listings.add(new MovieListing(movieId, title, genre, durationMin, rating, entry.getKey(),
                    cinemaScreenings.stream().map(Screening::startTime).toList(),
                    cinemaScreenings.stream().mapToDouble(Screening::pricePerTicket).min().orElse(0),
                    cinemaScreenings));
        }
    }

    private Screening screening(String screeningId, String cinemaId, String cinemaName,
                                String startTime, String endTime, String hall, String format,
                                String language, double price, int remainingSeats) {
        return new Screening(screeningId, "", "", cinemaId, cinemaName, startTime, endTime,
                hall, format, language, price, remainingSeats);
    }

    private List<Screening> expandScreenings(List<Screening> screenings, int durationMin) {
        List<Screening> expanded = new ArrayList<>();
        for (Screening screening : screenings == null ? List.<Screening>of() : screenings) {
            expanded.add(screening);
            extraScreening(screening, durationMin).ifPresent(expanded::add);
        }
        return expanded;
    }

    private java.util.Optional<Screening> extraScreening(Screening screening, int durationMin) {
        int start = timeToMinutes(screening.startTime());
        if (start <= 0 || start >= 21 * 60) return java.util.Optional.empty();
        int extraStart = start + 180;
        if (extraStart > 21 * 60) return java.util.Optional.empty();
        return java.util.Optional.of(new Screening(
                screening.screeningId() + "-B",
                screening.movieId(),
                screening.movieTitle(),
                screening.cinemaId(),
                screening.cinemaName(),
                formatTime(extraStart),
                formatTime(extraStart + durationMin),
                screening.hall() + " 加映",
                screening.format(),
                screening.language(),
                Math.max(45, screening.pricePerTicket() - 8),
                Math.max(12, screening.remainingSeats() - 10)
        ));
    }

    public List<MovieListing> findByCinema(String cinemaId) {
        return listings.stream()
                .filter(m -> m.cinemaId().equalsIgnoreCase(cinemaId))
                .toList();
    }

    public List<MovieListing> search(String genre, String keyword) {
        String normalizedKeyword = keyword == null ? "" : keyword.toLowerCase(Locale.ROOT);
        return listings.stream()
                .filter(m -> genre == null || genre.isBlank() || m.genre().contains(genre))
                .filter(m -> normalizedKeyword.isBlank()
                        || m.title().toLowerCase(Locale.ROOT).contains(normalizedKeyword)
                        || m.genre().toLowerCase(Locale.ROOT).contains(normalizedKeyword))
                .sorted(Comparator.comparingDouble(MovieListing::rating).reversed()
                        .thenComparing(MovieListing::title)
                        .thenComparing(MovieListing::cinemaId))
                .toList();
    }

    public List<MovieListing> searchByCinemaAndTime(String cinemaId, String afterTime) {
        int after = timeToMinutes(afterTime);
        return listings.stream()
                .filter(m -> cinemaId == null || cinemaId.isBlank() || m.cinemaId().equalsIgnoreCase(cinemaId))
                .map(listing -> listing.withScreenings(listing.screenings().stream()
                        .filter(screening -> after <= 0 || timeToMinutes(screening.startTime()) >= after)
                        .toList()))
                .filter(listing -> !listing.screenings().isEmpty())
                .sorted(Comparator.comparing((MovieListing listing) -> listing.screenings().get(0).startTime())
                        .thenComparing(MovieListing::title))
                .toList();
    }

    private int timeToMinutes(String time) {
        if (time == null || time.isBlank()) return 0;
        String[] parts = time.split(":");
        if (parts.length < 2) return 0;
        int hour = Integer.parseInt(parts[0]);
        int minute = Integer.parseInt(parts[1]);
        return hour * 60 + minute;
    }

    private String formatTime(int minutes) {
        int normalized = Math.floorMod(minutes, 24 * 60);
        return String.format(Locale.ROOT, "%02d:%02d", normalized / 60, normalized % 60);
    }

    public record MovieListing(
            String movieId,
            String title,
            String genre,
            int durationMinutes,
            double rating,
            String cinemaId,
            List<String> showtimes,
            double pricePerTicket,
            List<Screening> screenings
    ) {
        public MovieListing {
            showtimes = showtimes == null ? List.of() : List.copyOf(showtimes);
            screenings = screenings == null ? List.of() : List.copyOf(screenings);
        }

        MovieListing withScreenings(List<Screening> nextScreenings) {
            List<Screening> safe = nextScreenings == null ? List.of() : List.copyOf(nextScreenings);
            return new MovieListing(movieId, title, genre, durationMinutes, rating, cinemaId,
                    safe.stream().map(Screening::startTime).toList(),
                    safe.stream().mapToDouble(Screening::pricePerTicket).min().orElse(pricePerTicket),
                    safe);
        }
    }

    public record Screening(
            String screeningId,
            String movieId,
            String movieTitle,
            String cinemaId,
            String cinemaName,
            String startTime,
            String endTime,
            String hall,
            String format,
            String language,
            double pricePerTicket,
            int remainingSeats
    ) {
        Screening withMovie(String nextMovieId, String nextMovieTitle) {
            return new Screening(screeningId, nextMovieId, nextMovieTitle, cinemaId, cinemaName,
                    startTime, endTime, hall, format, language, pricePerTicket, remainingSeats);
        }
    }
}
