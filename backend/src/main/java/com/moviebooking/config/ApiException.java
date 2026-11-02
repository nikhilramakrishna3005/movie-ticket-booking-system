package com.moviebooking.config;

import org.springframework.http.HttpStatus;

/** A business-rule failure with a documented error code, rendered by {@link ApiExceptionHandler}. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }

    public static ApiException badRequest(String code, String message, Object details) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message, details);
    }

    public static ApiException conflict(String code, String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, details);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Object details() {
        return details;
    }
}
