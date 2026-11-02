package com.moviebooking.theater;

import java.math.BigDecimal;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ShowtimeRepository extends JpaRepository<Showtime, Long> {

    @Query(value = """
            SELECT t.name AS theaterName, sc.name AS screenName, c.name AS cityName, c.state_code AS stateCode
            FROM showtime st
            JOIN screen sc ON sc.id = st.screen_id
            JOIN theater t ON t.id = sc.theater_id
            JOIN city c ON c.id = t.city_id
            WHERE st.id = :showtimeId""", nativeQuery = true)
    Optional<VenueNames> findVenueNames(@Param("showtimeId") long showtimeId);

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
        String getTheaterName();

        String getScreenName();

        String getCityName();

        String getStateCode();
    }
}
