package com.weekendplanner.provider;

import com.weekendplanner.mock.MockMovieDatabase;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SandboxMovieListingProvider implements MovieListingProvider {

    private final MockMovieDatabase database = new MockMovieDatabase();

    @Override
    public List<MovieListing> search(String genre, String keyword) {
        return database.search(genre, keyword).stream()
                .map(this::map)
                .toList();
    }

    @Override
    public List<MovieListing> searchByCinemaAndTime(String cinemaId, String afterTime) {
        return database.searchByCinemaAndTime(cinemaId, afterTime).stream()
                .map(this::map)
                .toList();
    }

    private MovieListing map(MockMovieDatabase.MovieListing listing) {
        return new MovieListing(listing.movieId(), listing.title(), listing.genre(), listing.durationMinutes(),
                listing.rating(), listing.cinemaId(), listing.showtimes(), listing.pricePerTicket());
    }
}
