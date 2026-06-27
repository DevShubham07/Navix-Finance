package com.navix.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JwtService}: the HS256 issue/verify round-trip, audience separation,
 * cross-secret rejection, expiry handling, and garbage-token tolerance.
 */
class JwtServiceTest {

    private static final String SECRET_A = "secretA-secretA-secretA-secretA-32";
    private static final String SECRET_B = "secretB-secretB-secretB-secretB-32";

    @Test
    void issueVerify_roundTrip_forStaffToken() {
        JwtService jwt = new JwtService(SECRET_A, 3600);

        String token = jwt.issue("42", "Meera Krishnan", "ADMIN", JwtService.AUDIENCE_STAFF);
        JwtService.Principal p = jwt.verify(token);

        assertThat(p.id()).isEqualTo("42");
        assertThat(p.name()).isEqualTo("Meera Krishnan");
        assertThat(p.role()).isEqualTo("ADMIN");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_STAFF);
    }

    @Test
    void issueVerify_roundTrip_forBorrowerToken() {
        JwtService jwt = new JwtService(SECRET_A, 3600);

        String token = jwt.issue("9000001", "Asha", "BORROWER", JwtService.AUDIENCE_BORROWER);
        JwtService.Principal p = jwt.tryVerify(token);

        assertThat(p).isNotNull();
        assertThat(p.id()).isEqualTo("9000001");
        assertThat(p.name()).isEqualTo("Asha");
        assertThat(p.role()).isEqualTo("BORROWER");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_BORROWER);
    }

    @Test
    void tokenSignedByOneSecret_failsVerificationUnderAnother() {
        JwtService signer = new JwtService(SECRET_A, 3600);
        JwtService other = new JwtService(SECRET_B, 3600);

        String token = signer.issue("42", "Meera", "ADMIN", JwtService.AUDIENCE_STAFF);

        assertThat(other.tryVerify(token)).isNull();
        assertThatThrownBy(() -> other.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void expiredToken_isRejected() {
        // ttl = -1s → the token's expiry is already in the past at issue time.
        JwtService jwt = new JwtService(SECRET_A, -1);

        String token = jwt.issue("42", "Meera", "ADMIN", JwtService.AUDIENCE_STAFF);

        assertThat(jwt.tryVerify(token)).isNull();
        assertThatThrownBy(() -> jwt.verify(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void garbageString_tryVerify_returnsNull() {
        JwtService jwt = new JwtService(SECRET_A, 3600);

        assertThat(jwt.tryVerify("not-a-jwt")).isNull();
    }
}
