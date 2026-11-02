package com.moviebooking.config;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * The resource does not exist or the caller may not know it exists. Both cases map to 404 so that
 * another customer's hold or booking IDs cannot be probed.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException() {
        super("Resource not found");
    }
}
