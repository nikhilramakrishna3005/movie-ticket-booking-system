package com.moviebooking.theater;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * A theater admin's request to schedule a showtime. {@code startsAtLocal} is wall-clock time in
 * the theater's city time zone, as an admin would enter it; prices apply per seat category.
 */
public record NewShowtime(Long movieId, Long screenId, String languageCode, LocalDateTime startsAtLocal,
        BigDecimal standardPrice, BigDecimal premiumPrice) {

    static final BigDecimal MAX_PRICE = new BigDecimal("1000.00");

    /** Fields that are missing or malformed, checked before any lookup or authorization. */
    public List<String> invalidFields() {
        List<String> invalid = new ArrayList<>();
        if (movieId == null) {
            invalid.add("movieId");
        }
        if (screenId == null) {
            invalid.add("screenId");
        }
        if (languageCode == null || !languageCode.matches("[a-z]{2}")) {
            invalid.add("languageCode");
        }
        if (startsAtLocal == null || startsAtLocal.getSecond() != 0 || startsAtLocal.getNano() != 0) {
            invalid.add("startsAtLocal");
        }
        if (!isValidPrice(standardPrice)) {
            invalid.add("standardPrice");
        }
        if (!isValidPrice(premiumPrice)) {
            invalid.add("premiumPrice");
        }
        return invalid;
    }

    /** Positive, whole cents, and within a sanity bound well inside show_seat's numeric(10, 2). */
    private static boolean isValidPrice(BigDecimal price) {
        return price != null && price.signum() > 0 && price.stripTrailingZeros().scale() <= 2
                && price.compareTo(MAX_PRICE) <= 0;
    }
}
