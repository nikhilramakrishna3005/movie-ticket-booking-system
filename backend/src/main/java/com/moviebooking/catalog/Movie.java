package com.moviebooking.catalog;

import java.time.LocalDate;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** A movie in the seeded catalog. */
@Entity
@Table(name = "movie")
public class Movie {

    @Id
    private Long id;

    @Column(nullable = false)
    private String title;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_language_code", nullable = false)
    private Language originalLanguage;

    @Column(name = "duration_minutes", nullable = false)
    private int durationMinutes;

    @Column(nullable = false)
    private String certification;

    @Column(name = "release_date", nullable = false)
    private LocalDate releaseDate;

    @ManyToMany
    @JoinTable(name = "movie_genre",
            joinColumns = @JoinColumn(name = "movie_id"),
            inverseJoinColumns = @JoinColumn(name = "genre_id"))
    private Set<Genre> genres;

    protected Movie() {
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Language getOriginalLanguage() {
        return originalLanguage;
    }

    public int getDurationMinutes() {
        return durationMinutes;
    }

    public String getCertification() {
        return certification;
    }

    public LocalDate getReleaseDate() {
        return releaseDate;
    }

    public Set<Genre> getGenres() {
        return genres;
    }
}
