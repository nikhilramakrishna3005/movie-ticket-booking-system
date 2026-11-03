package com.moviebooking.theater;

import java.util.ArrayList;
import java.util.List;

/** A theater admin's request to add a screen with its seating plan. */
public record NewScreen(String name, List<SeatLayout.Row> rows) {

    public static final int MAX_NAME_LENGTH = 40;

    public SeatLayout layout() {
        return new SeatLayout(rows);
    }

    /** Leading and trailing spaces are not part of the name, so "Screen 3 " cannot duplicate "Screen 3". */
    public String trimmedName() {
        return name == null ? null : name.strip();
    }

    public List<String> invalidFields() {
        List<String> invalid = new ArrayList<>();
        String trimmed = trimmedName();
        if (trimmed == null || trimmed.isEmpty() || trimmed.length() > MAX_NAME_LENGTH) {
            invalid.add("name");
        }
        invalid.addAll(layout().invalidFields());
        return invalid;
    }
}
