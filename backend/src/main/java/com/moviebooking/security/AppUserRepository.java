package com.moviebooking.security;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);

    @Query(value = "SELECT theater_id FROM theater_admin_assignment WHERE user_id = :userId ORDER BY theater_id",
            nativeQuery = true)
    List<Long> findAssignedTheaterIds(@Param("userId") long userId);
}
