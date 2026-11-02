package com.moviebooking.catalog;

import java.util.List;

/** {@code size} is the page size actually applied, which may be lower than the one requested. */
public record ShowtimePage(List<ShowtimeSearchResult> items, int page, int size, long totalItems, int totalPages) {
}
