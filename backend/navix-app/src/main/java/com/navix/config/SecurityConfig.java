package com.navix.config;

import com.navix.common.security.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Real auth (P6): a stateless JWT chain. {@link JwtAuthFilter} validates the bearer
 * token and populates both the {@code ActorContext} (services authorise via
 * {@code requireRole} + the SoD event-trail replay — unchanged) and the Spring
 * {@code SecurityContext}. Authentication is required for all business endpoints;
 * fine-grained role/SoD authorisation stays in the services, so the borrower and
 * staff namespaces (which share {@code /api/applications}) are kept apart by the
 * token's role rather than by URL.
 *
 * <p>The two exceptions are {@code /api/staff/**} and {@code /api/admin/**}, which ARE
 * gated by URL — on {@code ROLE_STAFF}, the token's audience claim promoted to an
 * authority by {@link JwtAuthFilter}. Service-level {@code requireRole} guards assumed
 * that boundary existed; before this matcher, nothing enforced it, and a borrower token
 * reached any staff route whose service lacked its own check.
 *
 * <p>Open (no token): {@code /api/auth/**} (login), the public marketing contact form
 * ({@code /api/contact}), the generic storage presign routes ({@code /api/storage/**} — kept open per
 * plan decision 6), actuator and the API docs.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** BCrypt for staff passwords (AuthController login + invite-accept). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(
                            "/api/auth/**",
                            "/api/contact",
                            "/api/storage/**",
                            "/actuator/**",
                            "/swagger-ui/**", "/swagger-ui.html",
                            "/v3/api-docs/**", "/v3/api-docs").permitAll()
                    // Staff-only namespaces, gated on the token's AUDIENCE (granted as ROLE_STAFF by
                    // JwtAuthFilter) rather than on the 9 individual staff roles — so a tenth role
                    // needs no edit here. Both path forms are listed on purpose: the bare path is a
                    // runtime-only footgun otherwise. Services keep their own requireRole/SoD checks;
                    // this is the namespace boundary those checks assumed but nothing enforced.
                    .requestMatchers("/api/staff", "/api/staff/**", "/api/admin/**").hasRole("STAFF")
                    .anyRequest().authenticated())
            .exceptionHandling(ex -> ex.authenticationEntryPoint(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .addFilterBefore(new JwtAuthFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
            // RequestLoggingFilter runs FIRST (before JwtAuthFilter) so the requestId MDC is set for
            // every request — including 401s — and it emits the one-line access log on the way out.
            .addFilterBefore(new RequestLoggingFilter(), JwtAuthFilter.class);
        return http.build();
    }
}
