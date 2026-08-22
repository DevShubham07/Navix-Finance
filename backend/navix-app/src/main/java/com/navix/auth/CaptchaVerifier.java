package com.navix.auth;

import com.navix.common.exception.BusinessException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Cloudflare Turnstile check for the pre-auth forms — the password logins and the two
 * forgot-password endpoints, the routes an attacker can drive without a token.
 *
 * <p>It sits <b>in front of</b> {@link AttemptLimiter} rather than beside it. The limiter is keyed on
 * the identifier being attacked, so a bot that can reach it does not just guess passwords, it can
 * also spend a real staffer's three attempts and hold them out of the console. A challenge the bot
 * cannot answer never reaches the counter, which closes both.
 *
 * <p><b>Unconfigured means off.</b> With no {@code navix.captcha.secret} this class is a no-op —
 * the same shape as {@code navix.sms.mock} and {@code navix.email.provider=log}, so local dev,
 * {@code ./mvnw test}, the Playwright suite and the demo seed script need no new configuration and
 * no code path of their own.
 *
 * <p>Three things are checked, not one — {@code success} alone is the common under-implementation:
 * <ul>
 *   <li><b>success</b> — the provider vouches for the token;</li>
 *   <li><b>action</b> — it was minted on the surface it is being spent on, so a token farmed from
 *       the forgot-password form cannot be replayed against staff sign-in;</li>
 *   <li><b>hostname</b> — it was minted on one of our own origins, so nobody can embed our public
 *       site key on their page and farm tokens there.</li>
 * </ul>
 * Cloudflare also enforces single use on its side, so replaying a spent token fails at {@code success}.
 */
@Component
public class CaptchaVerifier {

    private static final Logger log = LoggerFactory.getLogger(CaptchaVerifier.class);
    private static final String ENDPOINT = "https://challenges.cloudflare.com/turnstile/v0/siteverify";
    /** Cloudflare's documented ceiling; anything longer is not a token we minted. */
    private static final int MAX_TOKEN_LENGTH = 2048;

    private final String secret;
    private final Set<String> allowedHostnames;
    private final RestClient http;

    public CaptchaVerifier(@Value("${navix.captcha.secret:}") String secret,
                           @Value("${navix.captcha.hostnames:}") String hostnames) {
        this.secret = secret == null ? "" : secret.trim();
        this.allowedHostnames = hostnames == null ? Set.of() : Arrays.stream(hostnames.split(","))
                .map(String::trim)
                .filter(h -> !h.isEmpty())
                .map(h -> h.toLowerCase(java.util.Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
        // Short timeouts: this runs inline on the login path, so a hung provider must not hold the
        // request thread for the default (infinite) wait.
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().requestFactory(factory).baseUrl(ENDPOINT).build();
        if (isEnabled() && allowedHostnames.isEmpty()) {
            // Deliberately a warning, not a failure. Requiring the allowlist would mean a second
            // env var whose absence is a total sign-in outage — and CI clones the live ECS task-def,
            // so a dropped var is a real failure mode here. success + action still hold without it.
            log.warn("captcha enabled with no navix.captcha.hostnames allowlist — "
                    + "tokens minted on a third-party site embedding our site key will be accepted");
        }
    }

    /** True when a secret is configured — i.e. when {@link #verify} will actually challenge. */
    public boolean isEnabled() {
        return !secret.isEmpty();
    }

    /**
     * Reject the request unless {@code token} is a Turnstile response the provider vouches for,
     * minted for {@code expectedAction} on one of our own hostnames. No-op when unconfigured.
     *
     * @param expectedAction the surface's {@code action}, matching the widget's {@code action} option
     * @throws BusinessException {@code CAPTCHA_FAILED} (HTTP 422) when the challenge was not passed
     */
    public void verify(String token, String expectedAction) {
        if (!isEnabled()) {
            return;
        }
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) {
            throw failed();
        }
        Map<String, Object> resp;
        try {
            MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
            form.add("secret", secret);
            form.add("response", token);
            // No `remoteip`: behind the ALB the socket peer is the load balancer, and the
            // X-Forwarded-For alternative is client-supplied at the leftmost hop — so the value we
            // could send is either constant or spoofable. Neither is worth pinning a token to.
            @SuppressWarnings("unchecked")
            Map<String, Object> body = http.post()
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(Map.class);
            resp = body;
        } catch (Exception e) {
            // ponytail: fail OPEN on a transport error — a Cloudflare outage would otherwise be a
            // total sign-in outage for borrowers AND staff, and AttemptLimiter is still in the path
            // behind us. Fail closed here only if bot pressure ever makes that the lesser problem.
            log.warn("captcha verify unreachable, allowing request: {}", e.getMessage());
            return;
        }
        if (resp == null || !Boolean.TRUE.equals(resp.get("success"))) {
            log.warn("captcha rejected action={} errors={}", expectedAction,
                    resp == null ? "no-body" : resp.get("error-codes"));
            throw failed();
        }
        // A verified token still has to be the RIGHT token: minted for this surface, on our site.
        //
        // A MISMATCH is a hard reject. An ABSENT field is not: Cloudflare omits `action` entirely in
        // some responses (verified against the dummy-key path), and treating that as a mismatch
        // would reject every legitimate sign-in — a total lockout, triggered by a provider-side
        // response shape we do not control. It is not a bypass either: whether the field is echoed
        // is Cloudflare's decision, not the caller's, so an attacker cannot suppress it to dodge the
        // check. A token farmed on another surface still comes back carrying ITS action.
        Object action = resp.get("action");
        if (action == null) {
            log.warn("captcha response carried no action — surface binding not enforced (expected={})",
                    expectedAction);
        } else if (!expectedAction.equals(action)) {
            log.warn("captcha action mismatch expected={} got={}", expectedAction, action);
            throw failed();
        }
        Object hostname = resp.get("hostname");
        if (!allowedHostnames.isEmpty()
                && !(hostname instanceof String h
                        && allowedHostnames.contains(h.toLowerCase(java.util.Locale.ROOT)))) {
            log.warn("captcha hostname not allowed action={} got={}", expectedAction, hostname);
            throw failed();
        }
    }

    private BusinessException failed() {
        return new BusinessException("CAPTCHA_FAILED",
                "Could not confirm you are human. Please try again.");
    }
}
