package com.moviebooking.theater;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "screen")
public class Screen {

    @Id
    private Long id;

    @Column(name = "theater_id", nullable = false)
    private long theaterId;

    @Column(nullable = false)
    private String name;

    protected Screen() {
    }

    public Long getId() {
        return id;
    }

    public long getTheaterId() {
        return theaterId;
    }

    public String getName() {
        return name;
    }
}
