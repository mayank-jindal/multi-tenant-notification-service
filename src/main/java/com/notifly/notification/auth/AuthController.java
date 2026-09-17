package com.notifly.notification.auth;

import com.notifly.notification.auth.dto.CurrentUserResponse;
import com.notifly.notification.auth.dto.LoginRequest;
import com.notifly.notification.auth.dto.LoginResponse;
import com.notifly.notification.security.UserPrincipal;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Obtaining and inspecting access tokens.
 */
@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication", description = "Obtaining and inspecting access tokens")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    @Operation(
            summary = "Exchange credentials for an access token",
            description = """
                    Returns a signed JWT to be sent as `Authorization: Bearer <token>`.

                    The token carries the caller's tenant as a claim, and that claim is the only
                    source of tenancy for subsequent requests — an `X-Tenant-ID` header is never
                    honoured.

                    Failures are deliberately indistinguishable: an unknown email, a wrong
                    password and a disabled account all return the same response, so the endpoint
                    cannot be used to discover which addresses are registered.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Authenticated"),
            @ApiResponse(responseCode = "400", description = "Malformed request", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "401", description = "Invalid credentials", content = @io.swagger.v3.oas.annotations.media.Content),
            @ApiResponse(responseCode = "403", description = "Tenant suspended", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    @GetMapping("/me")
    @Operation(
            summary = "Describe the currently authenticated user",
            security = @SecurityRequirement(name = "bearerAuth"))
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The caller's identity"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid token", content = @io.swagger.v3.oas.annotations.media.Content)
    })
    public ResponseEntity<CurrentUserResponse> currentUser(@AuthenticationPrincipal UserPrincipal principal) {
        return ResponseEntity.ok(authService.currentUser(principal));
    }
}
