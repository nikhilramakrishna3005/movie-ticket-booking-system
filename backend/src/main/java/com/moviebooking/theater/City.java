package com.moviebooking.theater;

import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "city")
public class City {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(name = "state_code", nullable = false)
    private String stateCode;

    @Column(name = "time_zone", nullable = false)
    private String timeZone;

    protected City() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getStateCode() {
        return stateCode;
    }

    public ZoneId getZone() {
        return ZoneId.of(timeZone);
    }
}
