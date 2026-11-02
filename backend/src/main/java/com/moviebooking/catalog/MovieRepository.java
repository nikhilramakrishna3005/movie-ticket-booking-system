package com.moviebooking.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface MovieRepository extends JpaRepository<Movie, Long> {

    @Query("""
            SELECT DISTINCT m FROM Movie m
            JOIN FETCH m.originalLanguage
            LEFT JOIN FETCH m.genres
            ORDER BY m.title""")
    List<Movie> findAllWithDetails();
}
