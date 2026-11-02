package com.moviebooking.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import com.moviebooking.config.ResourceNotFoundException;

class AccessPolicyTest {

    private static final AuthenticatedUser ALICE = customer(1L, "alice");
    private static final AuthenticatedUser BOB = customer(2L, "bob");
    private static final AuthenticatedUser DALLAS_ADMIN = admin(3L, "admin.dallas", Set.of(10L));
    private static final AuthenticatedUser MULTI_ADMIN = admin(4L, "admin.multi", Set.of(10L, 20L));
    private static final AuthenticatedUser UNASSIGNED_ADMIN = admin(5L, "admin.none", Set.of());

    @Test
    void ownerMayAccessOwnResource() {
        assertThatCode(() -> AccessPolicy.requireOwner(ALICE, 1L)).doesNotThrowAnyException();
    }

    @Test
    void anotherCustomersResourceLooksNonexistent() {
        assertThatThrownBy(() -> AccessPolicy.requireOwner(BOB, 1L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminIsNotTheOwnerEvenWithMatchingId() {
        assertThatThrownBy(() -> AccessPolicy.requireOwner(DALLAS_ADMIN, 3L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminMayManageAssignedTheaters() {
        assertThatCode(() -> AccessPolicy.requireAssignedTheater(DALLAS_ADMIN, 10L)).doesNotThrowAnyException();
        assertThatCode(() -> AccessPolicy.requireAssignedTheater(MULTI_ADMIN, 20L)).doesNotThrowAnyException();
    }

    @Test
    void adminIsDeniedOtherTheaters() {
        assertThatThrownBy(() -> AccessPolicy.requireAssignedTheater(DALLAS_ADMIN, 20L))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> AccessPolicy.requireAssignedTheater(UNASSIGNED_ADMIN, 10L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void customerIsDeniedTheaterAdministration() {
        assertThatThrownBy(() -> AccessPolicy.requireAssignedTheater(ALICE, 10L))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void erasedPrincipalHoldsNoPasswordHash() {
        AuthenticatedUser user = customer(9L, "erin");
        user.eraseCredentials();

        assertThat(user.getPassword()).isNull();
    }

    private static AuthenticatedUser customer(long id, String username) {
        return new AuthenticatedUser(id, username, "{noop}unused", username, Role.CUSTOMER, Set.of());
    }

    private static AuthenticatedUser admin(long id, String username, Set<Long> theaterIds) {
        return new AuthenticatedUser(id, username, "{noop}unused", username, Role.THEATER_ADMIN, theaterIds);
    }
}
