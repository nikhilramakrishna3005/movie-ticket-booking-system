package com.moviebooking.catalog;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;

import com.moviebooking.config.ApiException;

/**
 * Validated search criteria; null means "no filter". A null {@code date} means today in each
 * theater's local time zone. {@code q} matches movie titles and theater names, typo-tolerantly.
 */
public record ShowtimeSearch(Long cityId, LocalDate date, String q, String languageCode, String genre,
        Long movieId, int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 50;
    /** Bounds OFFSET so that deep pages cannot force PostgreSQL to scan and discard huge row counts. */
    public static final int MAX_PAGE = 1000;
    public static final int MAX_TEXT_LENGTH = 100;

    /**
     * Blank text filters are ignored. An oversized page size is capped rather than rejected
     * (AC-S5); a non-positive size or an out-of-range page is a client error.
     */
    public static ShowtimeSearch of(Long cityId, LocalDate date, String q, String language, String genre,
            Long movieId, Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0 || pageNumber > MAX_PAGE) {
            throw invalid("page", "page must be between 0 and " + MAX_PAGE);
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1) {
            throw invalid("size", "size must be at least 1");
        }
        String languageCode = text("language", language);
        return new ShowtimeSearch(cityId, date, text("q", q),
                languageCode == null ? null : languageCode.toLowerCase(Locale.ROOT), text("genre", genre), movieId,
                pageNumber, Math.min(pageSize, MAX_PAGE_SIZE));
    }

    long offset() {
        return (long) page * size;
    }

    private static String text(String parameter, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.length() > MAX_TEXT_LENGTH) {
            throw invalid(parameter, parameter + " must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return trimmed;
    }

    private static ApiException invalid(String parameter, String message) {
        return ApiException.badRequest("INVALID_REQUEST", message, Map.of("parameter", parameter));
    }
}
