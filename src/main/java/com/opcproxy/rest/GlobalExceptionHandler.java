package com.opcproxy.rest;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Единый формат ошибок API — RFC 7807 Problem Detail (ТЗ §5.9.6).
 */
@Slf4j
@RestControllerAdvice(basePackages = "com.opcproxy.rest")
public class GlobalExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(java.util.NoSuchElementException.class)
    public ProblemDetail notFound(java.util.NoSuchElementException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND,
                e.getMessage() != null ? e.getMessage() : "Resource not found");
        pd.setTitle("Not found");
        return pd;
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail conflict(IllegalStateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Operation not allowed");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail internal(Exception e) {
        log.error("REST internal error", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "Internal error: " + e.getMessage());
        pd.setTitle("Internal error");
        return pd;
    }
}