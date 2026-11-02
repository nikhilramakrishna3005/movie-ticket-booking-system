package com.moviebooking.theater;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A physical seat on a screen. */
@Entity
@Table(name = "seat")
public class Seat {

    @Id
    private Long id;

    @Column(name = "screen_id", nullable = false)
    private long screenId;

    @Column(name = "row_label", nullable = false)
    private String rowLabel;

    @Column(name = "seat_number", nullable = false)
    private int seatNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SeatCategory category;

    protected Seat() {
    }

    public Long getId() {
        return id;
    }

    public long getScreenId() {
        return screenId;
    }

    public String getRowLabel() {
        return rowLabel;
    }

    public int getSeatNumber() {
        return seatNumber;
    }

    public SeatCategory getCategory() {
        return category;
    }
}
