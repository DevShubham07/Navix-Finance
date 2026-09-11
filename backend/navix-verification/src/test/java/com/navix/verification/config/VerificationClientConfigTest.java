package com.navix.verification.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * Guards the timeout budget. The bureau step is a CHAIN — Digitap Credit Analytics (primary), then
 * Fintrix CRIF (fallback) — so the sum, not any single leg, is what has to fit inside the ALB idle
 * timeout in front of the service. Connect timeouts count too: each leg opens its own socket.
 */
class VerificationClientConfigTest {

    private static final Duration ALB_IDLE_TIMEOUT = Duration.ofSeconds(120);

    private static VerificationChainProperties defaults() {
        return new VerificationChainProperties(List.of("signzy", "digitap", "fintrix"),
                null, null, null, null, null, null, null);
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
     * The bureau chain is walked sequentially — Digitap Experian, then Fintrix CRIF, with the
     * {@code digitap-crif} leg off — so the read timeouts add up against a single 120s ALB idle timeout,
     * plus one connect timeout per leg. This is the test that stops another leg (or a generous re-tune
     * of an existing one) from silently blowing that budget.
     *
     * <p>The worst case is reachable on TIMEOUTS too, not only on slow-but-successful responses: since
     * {@code ProviderJson.post} wraps a raw transport failure (a read timeout included) in a
     * {@link com.navix.verification.exception.VerificationException} instead of letting it escape,
     * {@code RoutingVerificationPort.route()} falls through to the next leg on a timeout exactly as it
     * would on an HTTP error, rather than aborting the chain early.
     *
     * <p>The no-hit fall-through makes it reachable on a HEALTHY path as well: two legs that both answer
     * slowly, neither failing, now walk the whole budget where a leading no-hit used to end it.
     */
    @Test
    void theLiveTwoLegBureauChainFitsInsideTheAlbIdleTimeout() {
        VerificationChainProperties props = defaults();
        Duration worstCase = props.bureauReadTimeout()
                .plus(props.fintrixBureauReadTimeout())
                .plus(props.connectTimeout().multipliedBy(2));
        assertThat(worstCase).isEqualTo(Duration.ofSeconds(100));
        assertThat(worstCase).isLessThan(ALB_IDLE_TIMEOUT);
    }

    /**
     * Switching the {@code digitap-crif} flag on adds a third leg, and the budget no longer fits — the
     * older note in application.yml called it 110s and "10s of headroom" only because it never counted
     * the connect timeouts. Pinned so that re-enabling the flag is paired with raising the ALB idle
     * timeout rather than quietly exceeding it.
     */
    @Test
    void reEnablingDigitapCrifWouldExceedTheAlbIdleTimeout() {
        VerificationChainProperties props = defaults();
        Duration withCrifLeg = props.bureauReadTimeout()
                .plus(props.digitapCrifReadTimeout())
                .plus(props.fintrixBureauReadTimeout())
                .plus(props.connectTimeout().multipliedBy(3));
        assertThat(withCrifLeg).isEqualTo(Duration.ofSeconds(125));
        assertThat(withCrifLeg).isGreaterThan(ALB_IDLE_TIMEOUT);
    }

    /**
     * The code default has to match application.yml: tests carry no {@code navix.verification} block and
     * fall through to this, so a divergence would silently exercise a different provider order than
     * production does.
     */
    @Test
    void effectiveChainDefaultsToSignzyDigitapFintrix() {
        VerificationChainProperties unset =
                new VerificationChainProperties(null, null, null, null, null, null, null, null);
        assertThat(unset.effectiveChain()).containsExactly("signzy", "digitap", "fintrix");
        assertThat(new VerificationChainProperties(List.of(), null, null, null, null, null, null, null)
                .effectiveChain()).containsExactly("signzy", "digitap", "fintrix");
    }

    @Test
    void bureauNoHitFallThroughDefaultsToTrueAndIsExplicitlyDisablable() {
        assertThat(defaults().bureauNoHitFallThroughEnabled()).isTrue();
        assertThat(new VerificationChainProperties(null, null, null, null, null, null, null, false)
                .bureauNoHitFallThroughEnabled()).isFalse();
        assertThat(new VerificationChainProperties(null, null, null, null, null, null, null, true)
                .bureauNoHitFallThroughEnabled()).isTrue();
    }

    @Test
    void overridesWin_andNonsenseValuesFallBackToTheDefault() {
        VerificationChainProperties configured =
                new VerificationChainProperties(List.of("digitap"), 3, 20, 120, 8, 50, 25, null);
        assertThat(configured.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(configured.readTimeout()).isEqualTo(Duration.ofSeconds(20));
        assertThat(configured.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(120));
        assertThat(configured.signzyBureauReadTimeout()).isEqualTo(Duration.ofSeconds(8));
        assertThat(configured.fintrixBureauReadTimeout()).isEqualTo(Duration.ofSeconds(50));
        assertThat(configured.digitapCrifReadTimeout()).isEqualTo(Duration.ofSeconds(25));

        VerificationChainProperties nonsense =
                new VerificationChainProperties(null, 0, -1, 0, -5, 0, -3, null);
        assertThat(nonsense.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(nonsense.bureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(nonsense.fintrixBureauReadTimeout()).isEqualTo(Duration.ofSeconds(45));
        assertThat(nonsense.digitapCrifReadTimeout()).isEqualTo(Duration.ofSeconds(20));
    }

    /** Extracts the Jackson converter's configured supported media types from a built {@link RestClient}. */
    private static List<MediaType> jacksonSupportedMediaTypes(RestClient client) {
        List<HttpMessageConverter<?>> converters = new ArrayList<>();
        client.mutate().messageConverters(converters::addAll);
        return converters.stream()
                .filter(MappingJackson2HttpMessageConverter.class::isInstance)
                .findFirst()
                .map(HttpMessageConverter::getSupportedMediaTypes)
                .orElseThrow(() -> new AssertionError("No Jackson converter registered"));
    }

    /**
     * F2: every provider {@code RestClient} bean is built through {@code VerificationClientConfig}'s
     * shared {@code lenientJson} converter customisation, which was added because Signzy/Fintrix/Digitap
     * sometimes label a perfectly good JSON body {@code application/octet-stream} — the default Jackson
     * converter only accepts {@code application/json} and threw before the content was ever read.
     *
     * <p>The element-0 assertion is load-bearing, not cosmetic:
     * {@code AbstractHttpMessageConverter.getDefaultContentType()} returns the FIRST supported type, and
     * {@code DefaultRestClient} writes the outbound request body with a {@code null} contentType — so if
     * {@code application/octet-stream} (or anything else) sat ahead of
     * {@link MediaType#APPLICATION_JSON}, every outbound provider POST would silently change its own
     * {@code Content-Type} header, and providers would start rejecting us with 400/415.
     */
    @Test
    void everyProviderRestClientKeepsJsonFirstAndAcceptsOctetStream() {
        RestClient signzy = new VerificationClientConfig().signzyRestClient(
                new SignzyProperties("https://signzy.test", "tok", "cid", null, null), defaults());
        RestClient fintrix = new VerificationClientConfig().fintrixRestClient(
                new FintrixProperties("https://fintrix.test", "id", "secret"), defaults());
        RestClient digitapSvc = new VerificationClientConfig().digitapSvcRestClient(
                new DigitapProperties("https://svc.digitap.test", "https://api.digitap.test", "id", "secret"),
                defaults());

        for (RestClient client : List.of(signzy, fintrix, digitapSvc)) {
            List<MediaType> supported = jacksonSupportedMediaTypes(client);
            assertThat(supported).contains(MediaType.APPLICATION_OCTET_STREAM);
            assertThat(supported.get(0)).isEqualTo(MediaType.APPLICATION_JSON);
        }
    }
}
