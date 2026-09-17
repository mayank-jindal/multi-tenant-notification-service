package com.notifly.notification.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API documentation metadata and the bearer-token security scheme, so the Swagger UI can
 * authorise requests rather than only describe them.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI notiflyOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Multi-Tenant Notification Service")
                        .version("v1")
                        .description("""
                                Multi-channel notification delivery with tenant-defined templates,
                                scheduled and immediate sends, per-tenant rate limiting and
                                fairness, retries with exponential backoff, and a full delivery
                                audit trail.

                                **Authentication.** Call `POST /api/v1/auth/login`, then send the
                                returned token as `Authorization: Bearer <token>`. The token
                                carries your tenant; there is no tenant header.

                                **Errors** are RFC 7807 problem documents carrying a stable
                                machine-readable `code`.

                                **Delivery is at-least-once.** Duplicates are prevented wherever
                                the service has authority — submissions via `Idempotency-Key`, and
                                dispatch via a worker lease.
                                """)
                        .license(new License().name("Assignment submission")))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Token from POST /api/v1/auth/login")));
    }
}
