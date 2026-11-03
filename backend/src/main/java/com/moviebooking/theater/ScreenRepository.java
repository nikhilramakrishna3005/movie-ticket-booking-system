package com.moviebooking.theater;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScreenRepository extends JpaRepository<Screen, Long> {

    List<Screen> findByTheaterIdInOrderByNameAscIdAsc(Collection<Long> theaterIds);

    /**
     * Serializes showtime creation per screen. Without it, two concurrent overlapping inserts can
     * each wait on the other's entry in the exclusion constraint's GiST index and deadlock.
     * Layout replacement takes the same lock, so it can never interleave with a new showtime.
     */
    @Query(value = "SELECT * FROM screen WHERE id = :screenId FOR UPDATE", nativeQuery = true)
    Optional<Screen> lockForScheduling(@Param("screenId") long screenId);

    /** Transaction-local, like SET LOCAL; a longer wait fails with SQLSTATE 55P03. */
    @Query(value = "SELECT set_config('lock_timeout', :timeout, true)", nativeQuery = true)
    String setLocalLockTimeout(@Param("timeout") String timeout);
}
