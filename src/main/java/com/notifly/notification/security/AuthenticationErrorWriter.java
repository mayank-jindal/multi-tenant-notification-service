package com.notifly.notification.security;

import tools.jackson.databind.ObjectMapper;
import com.notifly.notification.common.error.ApiException;
import com.notifly.notification.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

/**
 * Writes RFC 7807 problem documents from inside the filter chain.
 *
 * <p>Security failures happen before Spring MVC's exception handling exists, so without this the
 * container would answer with an HTML error page — a different response shape for exactly the
 * errors a client is most likely to hit while integrating.
 */
@Component
public class AuthenticationErrorWriter {

    private static final URI TYPE_BASE = URI.create("https://notifly.io/problems/");

    private final ObjectMapper objectMapper;

    public AuthenticationErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, HttpServletRequest request, ApiException ex)
            throws IOException {
        write(response, request, ex.getStatus(), ex.getCode(), ex.getMessage());
    }

    public void write(HttpServletResponse response, HttpServletRequest request,
                      HttpStatus status, String code, String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(TYPE_BASE.resolve(code.toLowerCase().replace('_', '-')));
        problem.setTitle(status.getReasonPhrase());
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", Instant.now().toString());

        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }

    /** Entry point used when no credentials were supplied at all. */
    public void writeUnauthenticated(HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        write(response, request, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED,
                "Authentication is required to access this resource");
    }

    /** Entry point used when the caller is authenticated but lacks the required role. */
    public void writeForbidden(HttpServletResponse response, HttpServletRequest request)
            throws IOException {
        write(response, request, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                "You do not have permission to perform this action");
    }
}
