package com.moviebooking.catalog;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LanguageRepository extends JpaRepository<Language, String> {

    List<Language> findAllByOrderByNameAsc();
}
