package com.navix.verification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.verification.MandatePort;
import com.navix.verification.service.MockMandateAdapter;
import org.junit.jupiter.api.Test;

class DigioConfigTest {

    private static final VerificationChainProperties TIMEOUTS =
            new VerificationChainProperties(null, null, null, null, null, null, null, null);

    @Test
    void requestFactoryCarriesExplicitConnectAndReadTimeouts() {
        var f = DigioConfig.timeoutFactory(TIMEOUTS.connectTimeout(), TIMEOUTS.readTimeout());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(f, "connectTimeout"))
                .isEqualTo((int) TIMEOUTS.connectTimeout().toMillis());
        assertThat(org.springframework.test.util.ReflectionTestUtils.getField(f, "readTimeout"))
                .isEqualTo((int) TIMEOUTS.readTimeout().toMillis());
    }

    private static DigioProperties props(String clientId, String webhookSecret) {
        return new DigioProperties("digio", "https://api.digio.test", clientId, "secret", webhookSecret,
                "CFG1", null, 7);
    }

    @Test
    void refusesToStartWhenEnabledAndClientIdBlank() {
        assertThatThrownBy(() -> new DigioConfig().digioMandateAdapter(props(" ", "wh"), TIMEOUTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.digio.client-id")
                .hasMessageContaining("NAVIX_DIGIO_CLIENT_ID")
                .hasMessageContaining("navix.digio.provider=mock");
    }

    @Test
    void refusesToStartWhenEnabledAndWebhookSecretBlank() {
        assertThatThrownBy(() -> new DigioConfig().digioMandateAdapter(props("id", null), TIMEOUTS))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("navix.digio.webhook-secret");
    }

    @Test
    void startsWithMockProvider() {
        MandatePort port = new DigioConfig().mockMandateAdapter("dev");
        assertThat(port).isInstanceOf(MockMandateAdapter.class);
    }

    /** Booting on the mock in prod is legal (the flag may be off); serving a mandate on it is not. */
    @Test
    void mockRefusesToServeInProd() {
        MandatePort prod = new DigioConfig().mockMandateAdapter("prod");
        assertThatThrownBy(prod::liveBanks).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NAVIX_DIGIO_PROVIDER");
        assertThatThrownBy(() -> prod.fetch("ENA1")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void startsWithFullDigioConfig() {
        assertThat(new DigioConfig().digioMandateAdapter(props("id", "wh"), TIMEOUTS)).isNotNull();
    }
}
