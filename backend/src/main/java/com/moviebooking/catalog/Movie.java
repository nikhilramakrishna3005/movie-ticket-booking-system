package com.moviebooking.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only mapping of the movie columns used so far. */
@Entity
@Table(name = "movie")
public class Movie {

    @Id
    private Long id;

    @Column(nullable = false)
    private String title;

    protected Movie() {
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }
}
