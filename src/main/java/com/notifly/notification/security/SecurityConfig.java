package com.notifly.notification.security;

import com.notifly.notification.common.tenancy.TenantContextFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Authentication and authorization wiring.
 *
 * <p>Two design points are worth stating explicitly.
 *
 * <p><strong>Default deny.</strong> The rule chain ends in {@code anyRequest().authenticated()},
 * so an endpoint added tomorrow is protected by default and has to be deliberately opened.
 * Listing protected paths instead would mean every new endpoint is public until someone
 * remembers.
 *
 * <p><strong>Filter ordering.</strong> {@link TenantContextFilter} is registered inside this
 * chain, immediately after authorization, rather than as a free-standing servlet filter. It reads
 * the authenticated principal, so it must run after authentication has happened; placing it here
 * makes that ordering explicit instead of an accident of bean ordering.
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/auth/login",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    /**
     * The dashboard's static files.
     *
     * <p>Listed individually rather than as a wildcard. A pattern like {@code /**} would open
     * every unmatched path, which would quietly undo the default-deny posture the rest of this
     * chain depends on — and it would do so invisibly, because nothing would fail.
     *
     * <p>These files contain no data. Everything the dashboard displays is fetched from the same
     * authenticated API any other client uses.
     */
    private static final String[] STATIC_PATHS = {
            "/", "/index.html", "/app.js", "/styles.css", "/favicon.ico"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   AuthenticationErrorWriter errorWriter) throws Exception {
        return http
                // No browser session and no cookies, so there is no CSRF vector to protect and a
                // token cannot be replayed from one. Disabled deliberately, not by habit.
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(STATIC_PATHS).permitAll()
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        // Both produce problem documents; without these the container would
                        // return HTML for precisely the errors clients hit most while integrating.
                        .authenticationEntryPoint((request, response, ex) ->
                                errorWriter.writeUnauthenticated(response, request))
                        .accessDeniedHandler((request, response, ex) ->
                                errorWriter.writeForbidden(response, request)))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, errorWriter),
                        UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new TenantContextFilter(), AuthorizationFilter.class)
                .build();
    }

    /**
     * BCrypt at strength 12. The default of 10 is showing its age against modern hardware, and
     * the extra cost is paid once per login rather than per request.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
