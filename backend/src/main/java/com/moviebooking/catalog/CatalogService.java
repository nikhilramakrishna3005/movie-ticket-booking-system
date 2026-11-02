package com.moviebooking.catalog;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.moviebooking.config.ApiException;
import com.moviebooking.config.ResourceNotFoundException;

/** Read access to the seeded catalog. */
@Service
@Transactional(readOnly = true)
public class CatalogService {

    private final MovieRepository movies;
    private final LanguageRepository languages;

    public CatalogService(MovieRepository movies, LanguageRepository languages) {
        this.movies = movies;
        this.languages = languages;
    }

    public Movie getMovie(long movieId) {
        return movies.findById(movieId).orElseThrow(ResourceNotFoundException::new);
    }

    public List<MovieResponse> listMovies() {
        return movies.findAllWithDetails().stream().map(MovieResponse::of).toList();
    }

    /** The showtime's spoken language, which may differ from the movie's original language. */
    public void requireLanguage(String code) {
        if (code == null || !languages.existsById(code)) {
            throw ApiException.badRequest("INVALID_REQUEST", "Unknown language code", null);
        }
    }

    public record MovieResponse(long id, String title, LanguageInfo language, int durationMinutes,
            String certification, LocalDate releaseDate, List<String> genres) {

        static MovieResponse of(Movie movie) {
            Language language = movie.getOriginalLanguage();
            Set<Genre> genres = movie.getGenres();
            List<String> genreNames = genres == null ? List.of() : genres.stream()
                    .map(Genre::getName)
                    .sorted(Comparator.naturalOrder())
                    .toList();
            return new MovieResponse(movie.getId(), movie.getTitle(),
                    new LanguageInfo(language.getCode(), language.getName()), movie.getDurationMinutes(),
                    movie.getCertification(), movie.getReleaseDate(), genreNames);
        }
    }

    public record LanguageInfo(String code, String name) {
    }
}
