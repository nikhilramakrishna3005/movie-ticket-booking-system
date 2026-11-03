package com.moviebooking.theater;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TheaterRepository extends JpaRepository<Theater, Long> {

    List<Theater> findByIdInOrderByNameAsc(Collection<Long> ids);
}
