package com.navix.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Issues and verifies the HS256 JWTs that carry NAVIX identity. Two audiences —
 * {@code staff} (subject = staffId) and {@code borrower} (subject = applicantId) —
 * stay in separate namespaces (a borrower token can never satisfy a staff route).
 *
 * <p>The signing key is derived (SHA-256) from {@code navix.auth.secret} so any
 * configured secret length yields a valid 256-bit HMAC key. The secret is supplied
 * at runtime (env {@code AUTH_SECRET} / SSM) — never committed.
 */
@Component
public class JwtService {

    public static final String AUDIENCE_STAFF = "staff";
    public static final String AUDIENCE_BORROWER = "borrower";

    private final SecretKey key;
    private final long ttlSeconds;

    public JwtService(
            @Value("${navix.auth.secret:navix-local-dev-secret-change-me}") String secret,
            @Value("${navix.auth.ttl-seconds:86400}") long ttlSeconds) {
        this.key = Keys.hmacShaKeyFor(sha256(secret));
        this.ttlSeconds = ttlSeconds;
    }

    /** A verified token's identity. {@code id} is the actor id (staffId / applicantId). */
    public record Principal(String id, String name, String role, String audience) {
    }

    /** Issue a signed token. {@code role} is the actor role; {@code audience} = staff|borrower. */
    public String issue(String subjectId, String name, String role, String audience) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(subjectId)
                .claim("name", name)
                .claim("role", role)
                .audience().add(audience).and()
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlSeconds * 1000))
                .signWith(key)
                .compact();
    }

    /** Verify a token's signature/expiry and return its {@link Principal}; throws on any failure. */
    public Principal verify(String token) {
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        Claims c = jws.getPayload();
        String audience = c.getAudience() != null && !c.getAudience().isEmpty()
                ? c.getAudience().iterator().next() : null;
        return new Principal(c.getSubject(), c.get("name", String.class), c.get("role", String.class), audience);
    }

    /** Verify, swallowing failures into an {@link java.util.Optional}-style null. */
    public Principal tryVerify(String token) {
        try {
            return verify(token);
        } catch (JwtException | IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
