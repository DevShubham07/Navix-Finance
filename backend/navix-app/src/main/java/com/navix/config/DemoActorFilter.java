package com.navix.config;

import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Demo-mode identity resolution (§0.1 of handoff.md). Until real authentication ships, the acting
 * staff/borrower is taken from request headers so separation-of-duties and audit still work:
 *
 * <pre>
 *   X-Demo-Actor-Id    (default "demo")
 *   X-Demo-Actor-Name  (default "Demo User")
 *   X-Demo-Actor-Role  (default "ADMIN")
 * </pre>
 *
 * At go-live this filter is replaced by JWT authentication populating the same {@link ActorContext}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DemoActorFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CurrentActor actor = new CurrentActor(
                header(request, "X-Demo-Actor-Id", "demo"),
                header(request, "X-Demo-Actor-Name", "Demo User"),
                header(request, "X-Demo-Actor-Role", "ADMIN"));
        try {
            ActorContext.set(actor);
            filterChain.doFilter(request, response);
        } finally {
            ActorContext.clear();
        }
    }

    private static String header(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
