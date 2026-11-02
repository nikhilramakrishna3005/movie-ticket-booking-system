package com.moviebooking.theater;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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

    interface VenueNames {
        String getTheaterName();

        String getScreenName();

        String getCityName();

        String getStateCode();
    }
}
