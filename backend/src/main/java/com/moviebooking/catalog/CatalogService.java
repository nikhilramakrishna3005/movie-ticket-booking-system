package com.moviebooking.catalog;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ResourceNotFoundException;

/** Movie discovery and showtime search; read access to movies for other packages. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final MovieRepository movies;
    private final LanguageRepository languages;
    private final CatalogQueryRepository queries;
    private final Clock clock;

    public CatalogService(MovieRepository movies, LanguageRepository languages, CatalogQueryRepository queries,
            Clock clock) {
        this.movies = movies;
        this.languages = languages;
        this.queries = queries;
        this.clock = clock;
    }

    public Movie getMovie(long movieId) {
        return movies.findById(movieId).orElseThrow(ResourceNotFoundException::new);
    }

    public Optional<Movie> findMovie(long movieId) {
        return movies.findById(movieId);
    }

    public boolean languageExists(String code) {
        return languages.existsById(code);
    }

    public List<MovieView> listMovies() {
        return queries.findMovies();
    }

    /** Only scheduled showtimes that have not started at the current instant are returned. */
    public ShowtimePage searchShowtimes(ShowtimeSearch search) {
        Instant now = clock.instant();
        long total = queries.countShowtimes(search, now);
        List<ShowtimeSearchResult> items = search.offset() < total ? queries.searchShowtimes(search, now) : List.of();
        int totalPages = (int) ((total + search.size() - 1) / search.size());
        return new ShowtimePage(items, search.page(), search.size(), total, totalPages);
    }

    /** Any showtime, including past and cancelled ones; {@code status} tells them apart. */
    public ShowtimeView getShowtime(long showtimeId) {
        return queries.findShowtime(showtimeId).orElseThrow(ResourceNotFoundException::new);
    }
}
