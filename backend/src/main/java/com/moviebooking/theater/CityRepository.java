package com.moviebooking.theater;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CityRepository extends JpaRepository<City, Long> {

    List<City> findAllByOrderByNameAscStateCodeAsc();

    @Query(value = """
            SELECT c.* FROM city c
            JOIN theater t ON t.city_id = c.id
            WHERE t.id = :theaterId""", nativeQuery = true)
    Optional<City> findByTheaterId(@Param("theaterId") long theaterId);
}
