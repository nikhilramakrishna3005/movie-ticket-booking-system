package com.moviebooking.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Renders every error raised by a controller as {@link ApiError}. 401 and the URL-level 403 are
 * produced by the security filter chain in the same format (see SecurityConfig).
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> handleApiException(ApiException ex) {
        return ResponseEntity.status(ex.status()).body(new ApiError(ex.code(), ex.getMessage(), ex.details()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", ex.getMessage());
    }

    /**
     * A row-lock wait exceeded {@code lock_timeout} (SQLSTATE 55P03), or PostgreSQL broke a
     * deadlock (40P01). The transaction has rolled back, so nothing was changed and the client
     * may retry.
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> handleLockFailure(PessimisticLockingFailureException ex) {
        log.info("Lock wait failed, returning SEATS_BUSY: {}", ex.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "SEATS_BUSY", "The seats are being changed by another request; retry");
    }

    // Must be explicit: the catch-all below would otherwise turn service-level denials into 500.
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "You do not have permission to perform this action");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected server error");
    }

    /** Spring MVC's own exceptions (malformed JSON, type mismatch, unknown path, wrong method...). */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is4xxClientError() ? "CLIENT_ERROR" : "INTERNAL_ERROR";
        };
        String message = status.value() == 404 ? "Resource not found"
                : status.is4xxClientError() ? "Malformed or invalid request" : "Unexpected server error";
        return ResponseEntity.status(status).headers(headers).body(new ApiError(code, message, null));
    }

    private static ResponseEntity<ApiError> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new ApiError(code, message, null));
    }
}
