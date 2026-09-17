package com.notifly.notification.common.error;

import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

/**
 * Base class for errors that are a legitimate answer to the caller.
 *
 * <p>Carries the status and error code with it, so the exception handler translates rather than
 * decides. Anything that is genuinely a bug in this service should throw a plain runtime
 * exception instead and surface as a 500 — conflating the two hides real defects behind
 * reasonable-looking 4xx responses.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> properties = new HashMap<>();

    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public ApiException(HttpStatus status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
    }

    /** Adds a field to the problem document, for detail a client can act on. */
    public ApiException with(String key, Object value) {
        properties.put(key, value);
        return this;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, Object> getProperties() {
        return properties;
    }
}
