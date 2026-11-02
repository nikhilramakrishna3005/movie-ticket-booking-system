package com.moviebooking.theater;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.moviebooking.config.ApiException;

class ShowtimeSchedulingRulesTest {

    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    @Test
    void localStartIsConvertedWithTheOffsetInForceOnThatDate() {
        // CST (UTC-6) in winter, CDT (UTC-5) in summer: a fixed offset would get one of these wrong.
        assertThat(TheaterService.resolveLocalStart(LocalDateTime.parse("2026-12-01T19:00"), CHICAGO))
                .isEqualTo(Instant.parse("2026-12-02T01:00:00Z"));
        assertThat(TheaterService.resolveLocalStart(LocalDateTime.parse("2027-06-01T19:00"), CHICAGO))
                .isEqualTo(Instant.parse("2027-06-02T00:00:00Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "2027-03-14T02:30", // skipped: clocks jump from 02:00 to 03:00
            "2027-11-07T01:30"  // ambiguous: 01:00-02:00 happens twice
    })
    void daylightSavingGapsAndOverlapsAreRejected(String local) {
        assertThatThrownBy(() -> TheaterService.resolveLocalStart(LocalDateTime.parse(local), CHICAGO))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo("INVALID_START_TIME"));
    }

    @Test
    void completeRequestHasNoInvalidFields() {
        assertThat(request(new BigDecimal("12.50"), LocalDateTime.parse("2026-12-01T19:00")).invalidFields()).isEmpty();
    }

    @Test
    void missingFieldsAreAllReported() {
        assertThat(new NewShowtime(null, null, null, null, null, null).invalidFields())
                .containsExactly("movieId", "screenId", "languageCode", "startsAtLocal", "standardPrice",
                        "premiumPrice");
    }

    @ParameterizedTest
    @ValueSource(strings = { "0", "-1", "12.345", "1000.01" })
    void pricesMustBePositiveWholeCentsAndBounded(String price) {
        assertThat(request(new BigDecimal(price), LocalDateTime.parse("2026-12-01T19:00")).invalidFields())
                .containsExactly("standardPrice");
    }

    @Test
    void startTimeMustBeWholeMinutesAndLanguageALowerCaseCode() {
        NewShowtime request = new NewShowtime(1L, 1L, "EN", LocalDateTime.parse("2026-12-01T19:00:30"),
                BigDecimal.TEN, BigDecimal.TEN);

        assertThat(request.invalidFields()).containsExactly("languageCode", "startsAtLocal");
    }

    private static NewShowtime request(BigDecimal standardPrice, LocalDateTime startsAtLocal) {
        return new NewShowtime(1L, 1L, "en", startsAtLocal, standardPrice, new BigDecimal("16.00"));
    }
}
