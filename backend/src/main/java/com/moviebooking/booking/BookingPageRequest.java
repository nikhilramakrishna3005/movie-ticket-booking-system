package com.moviebooking.booking;

import java.util.Map;

import com.moviebooking.config.ApiException;

/** A validated page of the customer's booking history; the same bounds as the showtime search. */
public record BookingPageRequest(int page, int size) {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;
    /** Bounds OFFSET so that deep pages cannot force PostgreSQL to scan and discard huge row counts. */
    public static final int MAX_PAGE = 1000;

    /** An oversized page size is capped rather than rejected; a non-positive size or an out-of-range page is a client error. */
    public static BookingPageRequest of(Integer page, Integer size) {
        int pageNumber = page == null ? 0 : page;
        if (pageNumber < 0 || pageNumber > MAX_PAGE) {
            throw invalid("page", "page must be between 0 and " + MAX_PAGE);
        }
        int pageSize = size == null ? DEFAULT_PAGE_SIZE : size;
        if (pageSize < 1) {
            throw invalid("size", "size must be at least 1");
        }
        return new BookingPageRequest(pageNumber, Math.min(pageSize, MAX_PAGE_SIZE));
    }

    private static ApiException invalid(String parameter, String message) {
        return ApiException.badRequest("INVALID_REQUEST", message, Map.of("parameter", parameter));
    }
}
