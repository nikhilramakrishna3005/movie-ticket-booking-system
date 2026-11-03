package com.moviebooking.catalog;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public discovery endpoints; anonymous access is granted in SecurityConfig. */
@RestController
@RequestMapping("/api")
public class CatalogController {

    private final CatalogService catalogService;

    public CatalogController(CatalogService catalogService) {
        this.catalogService = catalogService;
    }

    @GetMapping("/movies")
    public List<MovieView> movies() {
        return catalogService.listMovies();
    }

    @GetMapping("/languages")
    public List<MovieView.LanguageView> languages() {
        return catalogService.listLanguages();
    }

    /** {@code date} is a calendar date in the theater's local time zone (ISO yyyy-MM-dd). */
    @GetMapping("/showtimes")
    public ShowtimePage searchShowtimes(@RequestParam(required = false) Long cityId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String q, @RequestParam(required = false) String language,
            @RequestParam(required = false) String genre, @RequestParam(required = false) Long movieId,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return catalogService.searchShowtimes(ShowtimeSearch.of(cityId, date, q, language, genre, movieId, page, size));
    }

    @GetMapping("/showtimes/{showtimeId}")
    public ShowtimeView showtime(@PathVariable long showtimeId) {
        return catalogService.getShowtime(showtimeId);
    }
}
