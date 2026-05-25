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
            double pricePerTicket
    ) {}
}
