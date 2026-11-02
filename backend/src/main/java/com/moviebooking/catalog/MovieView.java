package com.moviebooking.catalog;

import java.time.LocalDate;
import java.util.List;

public record MovieView(long id, String title, int durationMinutes, String certification, LocalDate releaseDate,
        LanguageView originalLanguage, List<String> genres) {

    public record LanguageView(String code, String name) {
    }
}
