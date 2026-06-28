package com.navix.config;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.security.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.MDC;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Real-auth identity resolution (replaces {@code DemoActorFilter}). Validates the
 * {@code Authorization: Bearer <jwt>} on each request: a valid token populates the
 * {@link ActorContext} (so services' {@code requireRole}/SoD keep working unchanged)
 * AND the Spring {@code SecurityContext} (so {@code .authenticated()} passes).
 *
 * <p>No token / invalid token → neither is set → the request fails closed (401 on a
 * protected route). Instantiated by {@code SecurityConfig} (not a {@code @Component},
 * to avoid double registration as a plain servlet filter).
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        JwtService.Principal principal = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            principal = jwtService.tryVerify(header.substring(7).trim());
        }
        try {
            if (principal != null && principal.id() != null) {
                ActorContext.set(new CurrentActor(principal.id(), principal.name(), principal.role()));
                // Enrich the log MDC so every line for this request is attributable (id/role only —
                // never the token or name). RequestLoggingFilter (outermost) clears the MDC.
                MDC.put("actorId", principal.id());
                MDC.put("actorRole", principal.role());
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.id(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role())));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
            filterChain.doFilter(request, response);
        } finally {
            // Thread-state must be cleared here; the actor MDC keys are cleared by RequestLoggingFilter
            // so they remain available for that filter's one-line access log after this returns.
            ActorContext.clear();
            SecurityContextHolder.clearContext();
        }
    }
}
