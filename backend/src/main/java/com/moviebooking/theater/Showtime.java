package com.moviebooking.theater;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A showing of a movie on one screen. New rows are inserted by {@link ShowtimeScheduler}. */
@Entity
@Table(name = "showtime")
public class Showtime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private long screenId;

    @Column(name = "movie_id", nullable = false)
    private long movieId;

    @Column(name = "language_code", nullable = false)
    private String languageCode;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowtimeStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Showtime() {
    }

    /** A scheduled showing. {@code endsAt} includes the turnover gap after the movie. */
    static Showtime schedule(long screenId, long movieId, String languageCode, Instant startsAt, Instant endsAt,
            Instant createdAt) {
        Showtime showtime = new Showtime();
        showtime.screenId = screenId;
        showtime.movieId = movieId;
        showtime.languageCode = languageCode;
        showtime.startsAt = startsAt;
        showtime.endsAt = endsAt;
        showtime.status = ShowtimeStatus.SCHEDULED;
        showtime.createdAt = createdAt;
        return showtime;
    }

    public Long getId() {
        return id;
    }

    public long getScreenId() {
        return screenId;
    }

    public long getMovieId() {
        return movieId;
    }

    public Instant getStartsAt() {
        return startsAt;
    }

    public ShowtimeStatus getStatus() {
        return status;
    }

    /** Seats can be held only for a scheduled showtime that has not started yet. */
    public boolean isOpenForHoldsAt(Instant now) {
        return status == ShowtimeStatus.SCHEDULED && startsAt.isAfter(now);
    }
}
