package com.moviebooking.theater;

import java.math.BigDecimal;
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

    interface VenueNames {
        Long getShowtimeId();

        String getTheaterName();

        String getScreenName();

        String getCityName();

        String getStateCode();

        String getTimeZone();
    }
}
