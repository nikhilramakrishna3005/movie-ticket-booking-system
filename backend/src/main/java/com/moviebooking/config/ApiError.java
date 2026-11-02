package com.moviebooking.config;

/** The JSON body of every error response (docs/architecture.md §10). */
public record ApiError(String code, String message, Object details) {
}
