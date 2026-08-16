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
import java.util.Locale;
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

    /** Request attribute carrying the verified staff token's {@code sid} claim, so {@code /staff/logout}
     *  can confirm it's clearing the CALLER's own session, not one that superseded it. */
    public static final String STAFF_SESSION_ID_ATTR = "navix.staffSessionId";

    private final JwtService jwtService;
    private final StaffSessionRegistry staffSessionRegistry;

    public JwtAuthFilter(JwtService jwtService, StaffSessionRegistry staffSessionRegistry) {
        this.jwtService = jwtService;
        this.staffSessionRegistry = staffSessionRegistry;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        JwtService.Principal principal = null;
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            principal = jwtService.tryVerify(header.substring(7).trim());
        }
        // Staff single-session: a token whose session id has been superseded is treated as no
        // token at all, so the request fails closed on any protected route (401) and the
        // superseded tab is kicked out on its very next call. Borrower tokens carry no sid and are
        // unaffected (see JwtService.Principal / StaffSessionRegistry).
        if (principal != null && JwtService.AUDIENCE_STAFF.equals(principal.audience())
                && !staffSessionRegistry.isCurrent(principal.id(), principal.sessionId())) {
            principal = null;
        }
        try {
            if (principal != null && principal.id() != null) {
                if (JwtService.AUDIENCE_STAFF.equals(principal.audience())) {
                    request.setAttribute(STAFF_SESSION_ID_ATTR, principal.sessionId());
                }
                ActorContext.set(new CurrentActor(principal.id(), principal.name(), principal.role()));
                // Enrich the log MDC so every line for this request is attributable (id/role only —
                // never the token or name). RequestLoggingFilter (outermost) clears the MDC.
                MDC.put("actorId", principal.id());
                MDC.put("actorRole", principal.role());
                // Two authorities: the actor role, and the token's AUDIENCE promoted to a role. The
                // audience is what makes JwtService's "a borrower token can never satisfy a staff
                // route" actually true — SecurityConfig gates /api/staff + /api/admin on ROLE_STAFF.
                // Until this was granted, the audience claim was minted, carried, and never read, so
                // a borrower token authenticated fine on every staff URL and was stopped only where
                // a service happened to call requireRole. A null audience yields the inert ROLE_NULL.
                var authentication = new UsernamePasswordAuthenticationToken(
                        principal.id(), null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()),
                                new SimpleGrantedAuthority(
                                        "ROLE_" + String.valueOf(principal.audience()).toUpperCase(Locale.ROOT))));
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
