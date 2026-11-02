package com.moviebooking.security;

import java.io.IOException;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Stateless HTTP Basic against the {@code app_user} table. Local assessment use only: Basic sends
 * credentials on every request, so any real deployment requires HTTPS.
 *
 * <p>No CORS configuration on purpose: the React app is served from the same origin (directly or
 * behind a reverse proxy), so browsers never send cross-origin API requests that need allowing.
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    static final String REALM = "movie-booking";

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        AuthenticationEntryPoint unauthenticated = SecurityConfig::writeUnauthenticated;
        http
                // CSRF needs an ambient credential to exploit; with no sessions or cookies the browser
                // only sends Basic credentials that the React app explicitly attaches.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.realmName(REALM).authenticationEntryPoint(unauthenticated))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden()))
                .authorizeHttpRequests(auth -> auth
                        // Lets Spring's error dispatch render 404/500 responses after a request was
                        // already authorized, instead of masking them as 401.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole(Role.THEATER_ADMIN.name())
                        .requestMatchers(HttpMethod.POST, "/api/showtimes/*/holds").hasRole(Role.CUSTOMER.name())
                        .requestMatchers("/api/holds/**", "/api/payments/**", "/api/bookings/**")
                        .hasRole(Role.CUSTOMER.name())
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll());
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * The {@code WWW-Authenticate} challenge is omitted for requests marked
     * {@code X-Requested-With: XMLHttpRequest} so the React login form, not the browser's native
     * dialog, handles a failed login.
     */
    private static void writeUnauthenticated(HttpServletRequest request,
            HttpServletResponse response, AuthenticationException ex)
            throws IOException {
        if (!"XMLHttpRequest".equals(request.getHeader("X-Requested-With"))) {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Basic realm=\"" + REALM + "\"");
        }
        writeError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "Valid credentials are required");
    }

    private static AccessDeniedHandler forbidden() {
        return (request, response, ex) -> writeError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this action");
    }

    private static void writeError(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"details\":null}");
    }
}
