package com.weekendplanner.provider;

import java.util.List;

public interface MovieListingProvider {

    List<MovieListing> search(String genre, String keyword);

    List<MovieListing> searchByCinemaAndTime(String cinemaId, String afterTime);

    record MovieListing(
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
        public MovieListing(String movieId,
                            String title,
                            String genre,
                            int durationMinutes,
                            double rating,
                            String cinemaId,
                            List<String> showtimes,
                            double pricePerTicket) {
            this(movieId, title, genre, durationMinutes, rating, cinemaId, showtimes, pricePerTicket, List.of());
        }

        public MovieListing {
            showtimes = showtimes == null ? List.of() : List.copyOf(showtimes);
            screenings = screenings == null ? List.of() : List.copyOf(screenings);
        }
    }

    record Screening(
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
    ) {}
}
