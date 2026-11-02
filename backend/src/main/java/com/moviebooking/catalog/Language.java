package com.moviebooking.catalog;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "language")
public class Language {

    @Id
    private String code;

    @Column(nullable = false)
    private String name;

    protected Language() {
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
