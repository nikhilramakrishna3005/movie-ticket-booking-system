package com.moviebooking.theater;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    @Query(value = """
            SELECT st.id AS showtimeId, t.name AS theaterName, sc.name AS screenName, c.name AS cityName,
                   c.state_code AS stateCode, c.time_zone AS timeZone
            FROM showtime st
            JOIN screen sc ON sc.id = st.screen_id
            JOIN theater t ON t.id = sc.theater_id
            JOIN city c ON c.id = t.city_id
            WHERE st.id IN (:showtimeIds)""", nativeQuery = true)
    List<VenueNames> findVenueNames(@Param("showtimeIds") Collection<Long> showtimeIds);

    /**
     * Seat inventory for a new showtime: one AVAILABLE row per physical seat of its screen, priced
     * by category. The theater package creates these rows; only booking changes them afterwards.
     */
    @Modifying
    @Query(value = """
            INSERT INTO show_seat (showtime_id, seat_id, screen_id, status, price)
            SELECT st.id, se.id, se.screen_id, 'AVAILABLE',
                   CASE se.category WHEN 'PREMIUM' THEN :premiumPrice ELSE :standardPrice END
            FROM showtime st
            JOIN seat se ON se.screen_id = st.screen_id
            WHERE st.id = :showtimeId""", nativeQuery = true)
    int createSeatInventory(@Param("showtimeId") long showtimeId, @Param("standardPrice") BigDecimal standardPrice,
            @Param("premiumPrice") BigDecimal premiumPrice);

    /** Any showtime, cancelled or past included: each one has inventory rows that reference the seats. */
    boolean existsByScreenId(long screenId);

    @Query(value = "SELECT DISTINCT screen_id FROM showtime WHERE screen_id IN (:screenIds)", nativeQuery = true)
    List<Long> findScreensWithShowtimes(@Param("screenIds") Collection<Long> screenIds);

    /**
     * A theater's showtimes starting in {@code [from, to)}, with prices and sales read from the
     * inventory. Read-only use of booking's {@code show_seat}; a hold past its expiry counts as free.
     */
    @Query(value = """
            SELECT st.id AS showtimeId, st.screen_id AS screenId, sc.name AS screenName, st.movie_id AS movieId,
                   st.language_code AS languageCode, st.status AS status, st.starts_at AS startsAt,
                   st.ends_at AS endsAt, count(ss.seat_id) AS seatCount,
                   count(ss.seat_id) FILTER (WHERE ss.status = 'BOOKED') AS bookedSeats,
                   count(ss.seat_id) FILTER (WHERE ss.status = 'HELD' AND ss.hold_expires_at > :now) AS heldSeats,
                   min(ss.price) FILTER (WHERE se.category = 'STANDARD') AS standardPrice,
                   min(ss.price) FILTER (WHERE se.category = 'PREMIUM') AS premiumPrice
            FROM showtime st
            JOIN screen sc ON sc.id = st.screen_id
            LEFT JOIN show_seat ss ON ss.showtime_id = st.id
            LEFT JOIN seat se ON se.id = ss.seat_id
            WHERE sc.theater_id = :theaterId AND st.starts_at >= :from AND st.starts_at < :to
            GROUP BY st.id, sc.name
            ORDER BY st.starts_at, sc.name, st.id""", nativeQuery = true)
    List<ScheduleRow> findSchedule(@Param("theaterId") long theaterId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to, @Param("now") OffsetDateTime now);

    interface VenueNames {
        Long getShowtimeId();

        String getTheaterName();

        String getScreenName();

        String getCityName();

        String getStateCode();

        String getTimeZone();
    }

    interface ScheduleRow {
        Long getShowtimeId();

        Long getScreenId();

        String getScreenName();

        Long getMovieId();

        String getLanguageCode();

        String getStatus();

        Instant getStartsAt();

        Instant getEndsAt();

        Long getSeatCount();

        Long getBookedSeats();

        Long getHeldSeats();

        BigDecimal getStandardPrice();

        BigDecimal getPremiumPrice();
    }
}
