package com.navix.verification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the timeout budget. The bureau step is a CHAIN — Signzy Experian, then Signzy CRIF, then
 * Digitap Credit Analytics — so the sum of the three, not any single one, is what has to fit inside
 * the ALB idle timeout in front of the service.
 */
class VerificationClientConfigTest {

    private static final Duration ALB_IDLE_TIMEOUT = Duration.ofSeconds(120);

    private static VerificationChainProperties defaults() {
        return new VerificationChainProperties(List.of("signzy", "digitap"), null, null, null, null);
    }

    @Test
    void digitapCreditGetsTheLongReadTimeoutThatTheThirtySecondDefaultWasCuttingOff() {
        assertThat(defaults().bureauReadTimeout()).isEqualTo(Duration.ofSeconds(90));
    }

    @Test
    void everythingElseKeepsTheShortDefaults() {
        assertThat(defaults().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults().readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theWholeBureauChainFitsInsideTheAlbIdleTimeout() {
        VerificationChainProperties props = defaults();
        Duration worstCase = props.signzyBureauReadTimeout()
                .plus(props.signzyBureauReadTimeout())
                .plus(props.bureauReadTimeout());
        assertThat(worstCase).isLessThan(ALB_IDLE_TIMEOUT);
    }

    @Test
    void overridesWin_andNonsenseValuesFallBackToTheDefault() {
        VerificationChainProperties configured =
                new VerificationChainProperties(List.of("digitap"), 3, 20, 120, 8);
        assertThat(configured.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(configured.readTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(configured.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(configured.signzyBureauReadTimeout()).isEqualTo(Duration.ofSeconds(8));

        VerificationChainProperties nonsense =
                new VerificationChainProperties(null, 0, -1, 0, -5);
        assertThat(nonsense.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(nonsense.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(90));
    }
}
