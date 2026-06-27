package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.verification.config.FintrixClientConfig;
import com.navix.verification.config.FintrixProperties;
import com.navix.verification.dto.FintrixDtos;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

/**
 * Live Fintrix sandbox smoke test — makes one real call per client against the configured
 * {@code navix.fintrix.*} sandbox. Disabled by default: it only runs when {@code FINTRIX_CLIENT_ID}
 * is present in the environment, so {@code ./mvnw test} skips it. Build it from env so no creds are
 * ever committed.
 */
@Tag("sandbox")
@EnabledIfEnvironmentVariable(named = "FINTRIX_CLIENT_ID", matches = ".+")
class FintrixSandboxSmokeIT {

    private static RestClient fintrixClient() {
        String baseUrl = envOrDefault("FINTRIX_BASE_URL", "https://admin.fintrix.tech/__api/api/v1/");
        FintrixProperties props = new FintrixProperties(
                baseUrl,
                System.getenv("FINTRIX_CLIENT_ID"),
                System.getenv("FINTRIX_CLIENT_SECRET"));
        return new FintrixClientConfig().fintrixRestClient(props);
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return v != null && !v.isBlank() ? v : fallback;
    }

    @Test
    void panComprehensive_returnsAResponse() {
        PanComprehensiveClient client = new PanComprehensiveClient(fintrixClient());

        FintrixDtos.PanResponse response = client.verify(
                envOrDefault("FINTRIX_TEST_PAN", "QVEPS0901K"), "smoke-pan");

        assertThat(response).isNotNull();
    }

    @Test
    void emailVerification_returnsAResponse() {
        EmailVerificationClient client = new EmailVerificationClient(fintrixClient());

        FintrixDtos.EmailVerificationResponse response = client.verify(
                envOrDefault("FINTRIX_TEST_EMAIL", "test@navix.example"),
                "Test User", "Digitap", "smoke-email");

        assertThat(response).isNotNull();
    }

    @Test
    void pennyDrop_returnsAResponse() {
        PennyDropClient client = new PennyDropClient(fintrixClient());

        FintrixDtos.PennyDropResponse response = client.verify(
                envOrDefault("FINTRIX_TEST_ACCOUNT", "1234567890"),
                envOrDefault("FINTRIX_TEST_IFSC", "HDFC0002557"), "smoke-pd");

        assertThat(response).isNotNull();
    }
}
