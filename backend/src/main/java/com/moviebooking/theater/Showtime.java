package com.moviebooking.theater;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Read-only mapping of the columns the booking flow needs. */
@Entity
@Table(name = "showtime")
public class Showtime {

    @Id
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private long screenId;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ShowtimeStatus status;

    protected Showtime() {
    }

    public Long getId() {
        return id;
    }

    public long getScreenId() {
        return screenId;
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
