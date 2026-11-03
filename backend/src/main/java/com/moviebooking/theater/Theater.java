package com.moviebooking.theater;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Theaters are seeded; there is no API that creates or changes them. */
@Entity
@Table(name = "theater")
public class Theater {

    @Id
    private Long id;

    @Column(name = "city_id", nullable = false)
    private long cityId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String address;

    protected Theater() {
    }

    public Long getId() {
        return id;
    }

    public long getCityId() {
        return cityId;
    }

    public String getName() {
        return name;
    }

    public String getAddress() {
        return address;
    }
}
