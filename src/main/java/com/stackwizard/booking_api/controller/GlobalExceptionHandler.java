package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CheckoutConflictError;
import com.stackwizard.booking_api.exception.CheckoutBlockedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;

import java.time.OffsetDateTime;
import java.util.List;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return buildError(HttpStatus.BAD_REQUEST, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        log.error("IllegalStateException on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        return buildError(HttpStatus.CONFLICT, ex.getMessage(), request.getRequestURI());
    }

    @ExceptionHandler(CheckoutBlockedException.class)
    public ResponseEntity<CheckoutConflictError> handleCheckoutBlocked(CheckoutBlockedException ex,
                                                                       HttpServletRequest request) {
        List<String> blockers = ex.getBlockers();
        CheckoutConflictError body = new CheckoutConflictError(
                OffsetDateTime.now(),
                HttpStatus.CONFLICT.value(),
                HttpStatus.CONFLICT.getReasonPhrase(),
                ex.getMessage(),
                request.getRequestURI(),
                blockers
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String message = ex.getMostSpecificCause() != null ? ex.getMostSpecificCause().getMessage() : ex.getMessage();
        return buildError(HttpStatus.BAD_REQUEST, message, request.getRequestURI());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                             HttpServletRequest request) {
        String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "HTTP method not supported";
        return buildError(HttpStatus.METHOD_NOT_ALLOWED, message, request.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {}: {}", request.getRequestURI(), ex.getMessage(), ex);
        String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : "Unexpected error";
        return buildError(HttpStatus.INTERNAL_SERVER_ERROR, message, request.getRequestURI());
    }

    private ResponseEntity<ApiError> buildError(HttpStatus status, String message, String path) {
        ApiError body = new ApiError(
                OffsetDateTime.now(),
                status.value(),
                status.getReasonPhrase(),
                message,
                path
        );
        return ResponseEntity.status(status).body(body);
    }

    public record ApiError(OffsetDateTime timestamp, int status, String error, String message, String path) {}
}
