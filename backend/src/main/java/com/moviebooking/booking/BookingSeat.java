package com.moviebooking.booking;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.springframework.data.domain.Persistable;

/**
 * A seat sold by a booking. booking_seat_showtime_seat_uk (showtime_id, seat_id) is the database
 * backstop that makes selling a seat twice impossible.
 */
@Entity
@Table(name = "booking_seat")
@IdClass(BookingSeat.Key.class)
public class BookingSeat implements Persistable<BookingSeat.Key> {

    @Id
    @Column(name = "booking_id")
    private UUID bookingId;

    @Id
    @Column(name = "seat_id")
    private long seatId;

    @Column(name = "showtime_id", nullable = false)
    private long showtimeId;

    @Transient
    private boolean isNew;

    protected BookingSeat() {
    }

    BookingSeat(UUID bookingId, long showtimeId, long seatId) {
        this.bookingId = bookingId;
        this.showtimeId = showtimeId;
        this.seatId = seatId;
        this.isNew = true;
    }

    @Override
    public Key getId() {
        return new Key(bookingId, seatId);
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        isNew = false;
    }

    public static class Key implements Serializable {

        private UUID bookingId;
        private long seatId;

        protected Key() {
        }

        Key(UUID bookingId, long seatId) {
            this.bookingId = bookingId;
            this.seatId = seatId;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key key && key.bookingId.equals(bookingId) && key.seatId == seatId;
        }

        @Override
        public int hashCode() {
            return bookingId.hashCode() * 31 + Long.hashCode(seatId);
        }
    }
}
