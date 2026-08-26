package com.navix.verification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Guards the timeout budget. The bureau step is a CHAIN — Fintrix (primary), then Digitap Credit
 * Analytics (fallback) — so the sum of the two, not any single one, is what has to fit inside the ALB
 * idle timeout in front of the service.
 */
class VerificationClientConfigTest {

    private static final Duration ALB_IDLE_TIMEOUT = Duration.ofSeconds(120);

    private static VerificationChainProperties defaults() {
        return new VerificationChainProperties(List.of("fintrix", "signzy", "digitap"),
                null, null, null, null, null, null);
    }

    @Test
    void digitapExperianGetsTheFortyFiveSecondReadTimeout_cutFromSixtyToMakeRoomForTheCrifLeg() {
        assertThat(defaults().bureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void digitapCrifGetsTheTightestBureauReadTimeout() {
        assertThat(defaults().digitapCrifReadTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    @Test
    void fintrixGetsTheFortyFiveSecondReadTimeout() {
        assertThat(defaults().fintrixBureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
    }

    @Test
    void everythingElseKeepsTheShortDefaults() {
        assertThat(defaults().connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(defaults().readTimeout()).isEqualTo(Duration.ofSeconds(30));
    }

    /**
     * The bureau chain is walked sequentially — Fintrix CRIF, then Digitap CRIF, then Digitap Experian
     * — so the three read timeouts add up against a single 120s ALB idle timeout. This is the test that
     * stops a fourth leg (or a generous re-tune of any existing one) from silently blowing that budget.
     */
    @Test
    void theWholeThreeLegBureauChainFitsInsideTheAlbIdleTimeout() {
        VerificationChainProperties props = defaults();
        Duration worstCase = props.fintrixBureauReadTimeout()
                .plus(props.digitapCrifReadTimeout())
                .plus(props.bureauReadTimeout());
        assertThat(worstCase).isEqualTo(Duration.ofSeconds(110));
        assertThat(worstCase).isLessThan(ALB_IDLE_TIMEOUT);
    }

    @Test
    void overridesWin_andNonsenseValuesFallBackToTheDefault() {
        VerificationChainProperties configured =
                new VerificationChainProperties(List.of("digitap"), 3, 20, 120, 8, 50, 25);
        assertThat(configured.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(configured.readTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(configured.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(configured.signzyBureauReadTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(configured.fintrixBureauReadTimeout()).isEqualTo(Duration.ofSeconds(50));
        assertThat(configured.digitapCrifReadTimeout()).isEqualTo(Duration.ofSeconds(25));

        VerificationChainProperties nonsense =
                new VerificationChainProperties(null, 0, -1, 0, -5, 0, -3);
        assertThat(nonsense.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(nonsense.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(nonsense.fintrixBureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(nonsense.digitapCrifReadTimeout()).isEqualTo(Duration.ofSeconds(20));
    }
}
