package com.moviebooking.booking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import com.moviebooking.config.ApiException;

class BookingPageRequestTest {

    @Test
    void defaultsToTheFirstPageOfTen() {
        assertThat(BookingPageRequest.of(null, null)).isEqualTo(new BookingPageRequest(0, 10));
    }

    @Test
    void anOversizedPageIsCappedNotRejected() {
        assertThat(BookingPageRequest.of(2, 1_000))
                .isEqualTo(new BookingPageRequest(2, BookingPageRequest.MAX_PAGE_SIZE));
    }

    @Test
    void acceptsTheBoundaryValues() {
        assertThat(BookingPageRequest.of(BookingPageRequest.MAX_PAGE, 1))
                .isEqualTo(new BookingPageRequest(BookingPageRequest.MAX_PAGE, 1));
    }

    @Test
    void rejectsNegativeAndTooDeepPages() {
        assertInvalid(-1, null, "page");
        assertInvalid(BookingPageRequest.MAX_PAGE + 1, null, "page");
    }

    @Test
    void rejectsANonPositiveSize() {
        assertInvalid(0, 0, "size");
        assertInvalid(0, -5, "size");
    }

    private static void assertInvalid(Integer page, Integer size, String parameter) {
        assertThatThrownBy(() -> BookingPageRequest.of(page, size))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_REQUEST");
                    assertThat(ex.details()).isEqualTo(Map.of("parameter", parameter));
                });
    }
}
