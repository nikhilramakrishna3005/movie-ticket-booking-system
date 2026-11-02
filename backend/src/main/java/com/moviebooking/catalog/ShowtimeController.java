package com.moviebooking.catalog;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.moviebooking.catalog.ShowtimeSearch.ShowtimePage;
import com.moviebooking.catalog.ShowtimeSearch.ShowtimeView;

@RestController
@RequestMapping("/api/showtimes")
public class ShowtimeController {

    private final ShowtimeSearch showtimeSearch;

    public ShowtimeController(ShowtimeSearch showtimeSearch) {
        this.showtimeSearch = showtimeSearch;
    }

    /**
     * Scheduled showtimes for one theater-local date. With no date, that date is today in
     * America/Chicago. {@code size} above {@link ShowtimeSearch#MAX_PAGE_SIZE} is capped.
     */
    @GetMapping
    public ShowtimePage search(@RequestParam(required = false) Long city,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate date,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String language,
            @RequestParam(required = false) String genre,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return showtimeSearch.search(city, date, q, language, genre, page, size);
    }

    @GetMapping("/{showtimeId}")
    public ShowtimeView showtime(@PathVariable long showtimeId) {
        return showtimeSearch.get(showtimeId);
    }
}
