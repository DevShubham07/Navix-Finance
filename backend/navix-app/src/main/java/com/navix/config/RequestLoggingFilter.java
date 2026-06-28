package com.navix.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Emits exactly ONE concise access line per request — {@code METHOD path -> status durationMs
 * actor=id/role} — and establishes a per-request correlation id ({@code requestId}) in the MDC so
 * every other log line for the same request carries it (and so do error/transition lines). Runs at
 * the very start of the security chain (before {@link JwtAuthFilter}), so the id is present even on a
 * 401; the actor is read from the MDC after the chain (JwtAuthFilter populates {@code actorId}/
 * {@code actorRole} on the way in and this filter — being outermost — clears the whole MDC at the end).
 *
 * <p>PII-safe by construction: it logs the request PATH only, never the query string (search/customer
 * endpoints carry {@code q=name|mobile|PAN}) and never a request/response body. The
 * {@code /actuator/health} probe — hit roughly every 30s by the container HEALTHCHECK and the ALB
 * target group — is skipped so it does not flood CloudWatch or inflate cost.
 *
 * <p>Instantiated by {@link SecurityConfig} (not a {@code @Component}, to avoid double registration).
 */
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger("com.navix.access");
    private static final String HEALTH_PATH = "/actuator/health";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        MDC.put("requestId", resolveRequestId(request));
        response.setHeader("X-Request-Id", MDC.get("requestId"));
        long startNanos = System.nanoTime();
        try {
            filterChain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (!HEALTH_PATH.equals(path)) {
                long ms = (System.nanoTime() - startNanos) / 1_000_000L;
                log.info("{} {} -> {} {}ms actor={}/{}",
                        request.getMethod(), path, response.getStatus(), ms,
                        orDash(MDC.get("actorId")), orDash(MDC.get("actorRole")));
            }
            // Outermost filter — clear the whole MDC so nothing leaks onto a reused worker thread.
            MDC.clear();
        }
    }

    /** Use a caller-supplied X-Request-Id (clamped) for cross-service correlation, else a short uuid. */
    private static String resolveRequestId(HttpServletRequest request) {
        String incoming = request.getHeader("X-Request-Id");
        if (incoming != null && !incoming.isBlank()) {
            String trimmed = incoming.trim();
            return trimmed.length() > 36 ? trimmed.substring(0, 36) : trimmed;
        }
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static String orDash(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }
}
