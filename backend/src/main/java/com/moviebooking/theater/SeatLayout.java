package com.moviebooking.theater;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A screen's seating plan as rows of numbered seats, front row first. Labels are single letters so
 * that alphabetical order is the physical order everywhere seats are listed.
 */
public record SeatLayout(List<Row> rows) {

    public static final int MAX_ROWS = 26;
    public static final int MAX_SEATS_PER_ROW = 40;

    /** {@code seatCount} seats numbered from 1, all of one category. */
    public record Row(String rowLabel, Integer seatCount, String category) {
    }

    /** Field paths such as {@code rows[2].seatCount} for every missing or invalid part. */
    public List<String> invalidFields() {
        List<String> invalid = new ArrayList<>();
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            invalid.add("rows");
            return invalid;
        }
        Set<String> labels = new HashSet<>();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            String path = "rows[" + i + "]";
            if (row == null) {
                invalid.add(path);
                continue;
            }
            if (row.rowLabel() == null || !row.rowLabel().matches("[A-Z]") || !labels.add(row.rowLabel())) {
                invalid.add(path + ".rowLabel");
            }
            if (row.seatCount() == null || row.seatCount() < 1 || row.seatCount() > MAX_SEATS_PER_ROW) {
                invalid.add(path + ".seatCount");
            }
            if (Arrays.stream(SeatCategory.values()).noneMatch(category -> category.name().equals(row.category()))) {
                invalid.add(path + ".category");
            }
        }
        return invalid;
    }
}
