package com.navix.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration for NAVIX Finance.
 *
 * Two separate filter chains enforce the maker-checker separation of duties:
 *  - Borrower-facing chain: /api/borrower, /api/kyc, /api/loan (public-ish for now,
 *    token-based borrower auth to be wired later).
 *  - Staff chain: /api/staff, /api/credit, /api/disbursement, /api/collections,
 *    /api/income require authenticated staff with the appropriate role
 *    (Credit Executive != Credit Head != Disbursement Head != Accountant).
 *
 * TODO: replace permissive rules with real authentication (JWT/session) and
 *       role-based authorization. Kept permissive for scaffold so the app boots.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /** BCrypt for staff passwords (AuthController login + invite-accept). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Borrower-facing endpoints. Permissive for now.
     * TODO: add borrower token authentication.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain borrowerFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/borrower/**", "/api/kyc/**", "/api/loan/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // TODO: tighten — require borrower token instead of permitAll.
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Staff endpoints (credit, disbursement, collections, income, generic staff).
     * Permissive for now.
     * TODO: require staff authentication + role checks per separation of duties.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain staffFilterChain(HttpSecurity http) throws Exception {
        http
            .securityMatcher("/api/staff/**", "/api/credit/**", "/api/disbursement/**",
                    "/api/collections/**", "/api/income/**")
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // TODO: replace with .authenticated() + role-based rules.
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    /**
     * Default chain for everything else (docs, actuator, etc.). Permissive.
     * TODO: lock down actuator + swagger as needed.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain defaultFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
