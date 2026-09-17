package com.notifly.notification.common.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates every exception into an RFC 7807 problem document.
 *
 * <p>Controllers never build error bodies themselves. One handler means one response shape, and
 * it means the shape cannot drift as endpoints are added.
 *
 * <p>The distinction that matters here is between errors that answer the caller and errors that
 * reveal a defect. {@link ApiException} and the framework's own request-shape exceptions are
 * answers: they carry a stable code and an actionable message. Everything else is a bug, and is
 * logged at ERROR with a stack trace and returned as an opaque 500 — the caller learns nothing
 * about our internals, and we learn everything from the log.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final URI TYPE_BASE = URI.create("https://notifly.io/problems/");

    // ------------------------------------------------------------------ application errors

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(ex.getStatus(), ex.getCode(), ex.getMessage(), request);
        ex.getProperties().forEach(problem::setProperty);

        if (ex.getStatus().is5xxServerError()) {
            log.error("Application error {} on {}", ex.getCode(), request.getRequestURI(), ex);
        } else {
            log.debug("Client error {} on {}: {}", ex.getCode(), request.getRequestURI(), ex.getMessage());
        }

        HttpHeaders headers = new HttpHeaders();
        // A 429 is only actionable if the client is told how long to wait.
        Object retryAfter = ex.getProperties().get("retryAfterSeconds");
        if (ex.getStatus() == HttpStatus.TOO_MANY_REQUESTS && retryAfter != null) {
            headers.add(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
        }
        return new ResponseEntity<>(problem, headers, ex.getStatus());
    }

    // ------------------------------------------------------------------ validation

    /**
     * Body validation failures. Every violation is reported, not just the first — a client
     * fixing one field at a time across four round trips is a bad API.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBodyValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<Map<String, String>> violations = new ArrayList<>();
        ex.getBindingResult().getFieldErrors().forEach(error -> violations.add(Map.of(
                "field", error.getField(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage())));
        ex.getBindingResult().getGlobalErrors().forEach(error -> violations.add(Map.of(
                "field", error.getObjectName(),
                "message", error.getDefaultMessage() == null ? "is invalid" : error.getDefaultMessage())));

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request);
        problem.setProperty("violations", violations);
        return problem;
    }

    /** Validation failures on path variables and request parameters. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<Map<String, String>> violations = ex.getConstraintViolations().stream()
                .map(violation -> Map.of(
                        "field", String.valueOf(violation.getPropertyPath()),
                        "message", violation.getMessage()))
                .toList();

        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Request validation failed", request);
        problem.setProperty("violations", violations);
        return problem;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        // The parser's message can quote the offending payload, so it is deliberately not echoed.
        return problem(HttpStatus.BAD_REQUEST, ErrorCode.MALFORMED_REQUEST,
                "Request body is missing or is not valid JSON", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Parameter '" + ex.getName() + "' has the wrong type", request);
        problem.setProperty("parameter", ex.getName());
        return problem;
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ProblemDetail handleMissingParameter(MissingServletRequestParameterException ex,
                                                HttpServletRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                "Required parameter '" + ex.getParameterName() + "' is missing", request);
        problem.setProperty("parameter", ex.getParameterName());
        return problem;
    }

    // ------------------------------------------------------------------ security

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        // Deliberately vague: distinguishing "no such user" from "wrong password" turns the
        // login endpoint into an account enumeration oracle.
        return problem(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED,
                "Authentication is required and has failed or not been supplied", request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.debug("Access denied on {}", request.getRequestURI());
        return problem(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action", request);
    }

    // ------------------------------------------------------------------ routing and concurrency

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {
        return problem(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, "No such endpoint", request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                  HttpServletRequest request) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED,
                "Method " + ex.getMethod() + " is not supported by this endpoint", request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ProblemDetail handleMediaType(HttpMediaTypeNotSupportedException ex, HttpServletRequest request) {
        return problem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ErrorCode.UNSUPPORTED_MEDIA_TYPE,
                "Content type is not supported; use application/json", request);
    }

    /**
     * A concurrent modification. The dispatcher and the tenant-facing API can touch the same
     * notification, so this is a real outcome rather than a defect, and the caller is told to
     * retry rather than shown a 500.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex,
                                              HttpServletRequest request) {
        log.debug("Optimistic lock conflict on {}", request.getRequestURI());
        return problem(HttpStatus.CONFLICT, ErrorCode.OPTIMISTIC_LOCK,
                "The resource was modified concurrently; reload it and retry", request);
    }

    // ------------------------------------------------------------------ catch-all

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest request) {
        // Anything reaching here is a defect. Log everything, disclose nothing.
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ErrorCode.INTERNAL_ERROR,
                "An unexpected error occurred", request);
    }

    // ------------------------------------------------------------------ helper

    private ProblemDetail problem(HttpStatus status, String code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());
        return problem;
    }
}
