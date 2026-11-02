package com.moviebooking.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.moviebooking.config.ApiException;

class ShowtimeSearchTest {

    @Test
    void defaultsToFirstPageOfTwenty() {
        ShowtimeSearch search = ShowtimeSearch.of(null, null, null, null, null, null, null, null);

        assertThat(search.page()).isZero();
        assertThat(search.size()).isEqualTo(ShowtimeSearch.DEFAULT_PAGE_SIZE);
        assertThat(search.offset()).isZero();
    }

    @Test
    void oversizedPageSizeIsCappedNotRejected() {
        assertThat(ShowtimeSearch.of(null, null, null, null, null, null, 0, 500).size())
                .isEqualTo(ShowtimeSearch.MAX_PAGE_SIZE);
        assertThat(ShowtimeSearch.of(null, null, null, null, null, null, 3, 50).offset()).isEqualTo(150);
    }

    @Test
    void outOfRangePagingIsRejectedWithTheParameterName() {
        assertInvalid(() -> ShowtimeSearch.of(null, null, null, null, null, null, -1, 10), "page");
        assertInvalid(() -> ShowtimeSearch.of(null, null, null, null, null, null, ShowtimeSearch.MAX_PAGE + 1, 10),
                "page");
        assertInvalid(() -> ShowtimeSearch.of(null, null, null, null, null, null, 0, 0), "size");
    }

    @Test
    void textFiltersAreTrimmedBlankIsIgnoredAndLanguageIsLowerCased() {
        ShowtimeSearch search = ShowtimeSearch.of(1L, LocalDate.of(2026, 11, 3), "  Intersteller ", " EN ", "   ",
                null, null, null);

        assertThat(search.q()).isEqualTo("Intersteller");
        assertThat(search.languageCode()).isEqualTo("en");
        assertThat(search.genre()).isNull();
    }

    @Test
    void overlongTextIsRejected() {
        assertInvalid(() -> ShowtimeSearch.of(null, null, "x".repeat(ShowtimeSearch.MAX_TEXT_LENGTH + 1), null, null,
                null, null, null), "q");
    }

    private static void assertInvalid(Runnable call, String parameter) {
        assertThatThrownBy(call::run)
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo("INVALID_REQUEST");
                    assertThat(ex.details()).isEqualTo(Map.of("parameter", parameter));
                });
    }
}
