package com.moviebooking.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "genre")
public class Genre {

    @Id
    private Long id;

    @Column(nullable = false)
    private String name;

    protected Genre() {
    }

    public String getName() {
        return name;
    }
}
