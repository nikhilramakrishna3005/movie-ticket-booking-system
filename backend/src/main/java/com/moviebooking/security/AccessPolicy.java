package com.moviebooking.security;

import org.springframework.security.access.AccessDeniedException;

import com.moviebooking.config.ResourceNotFoundException;

/**
 * Resource-level authorization rules that URL patterns cannot express. Services call these after
 * loading the resource and before acting on it.
 */
public final class AccessPolicy {

    private AccessPolicy() {
    }

    /**
     * Customers may only act on their own holds and bookings. A mismatch is reported as 404, not
     * 403, so the caller learns nothing about other customers' resources.
     */
    public static void requireOwner(AuthenticatedUser user, long ownerCustomerId) {
        if (user.role() != Role.CUSTOMER || user.id() != ownerCustomerId) {
            throw new ResourceNotFoundException();
        }
    }

    /** Theater admins may only manage theaters they are assigned to. */
    public static void requireAssignedTheater(AuthenticatedUser user, long theaterId) {
        if (!user.isTheaterAdmin() || !user.theaterIds().contains(theaterId)) {
            throw new AccessDeniedException("Not assigned to this theater");
        }
    }
}
