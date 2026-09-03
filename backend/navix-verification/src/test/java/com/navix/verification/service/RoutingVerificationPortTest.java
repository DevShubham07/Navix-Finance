package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.verification.VerificationPort.BureauCheck;
import com.navix.common.verification.VerificationPort.EmailCheck;
import com.navix.common.verification.VerificationPort.LivenessSession;
import com.navix.common.verification.VerificationPort.PanCheck;
import com.navix.common.verification.VerificationPort.PendingChallenge;
import com.navix.common.verification.VerificationPort.PennyDropCheck;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the {@link RoutingVerificationPort} chain semantics with mocked Fintrix (bureau primary),
 * Signzy and Digitap adapters: primary success wins, a {@link VerificationException} falls through to the
 * fallback, a {@link CapabilityNotSupportedException} skips to the next provider, and a capability no
 * provider can serve rethrows.
 */
class RoutingVerificationPortTest {

    private FintrixVerificationAdapter fintrix;
    private SignzyVerificationAdapter signzy;
    private DigitapVerificationAdapter digitap;
    private RoutingVerificationPort router;

    @BeforeEach
    void setUp() {
        fintrix = Mockito.mock(FintrixVerificationAdapter.class);
        signzy = Mockito.mock(SignzyVerificationAdapter.class);
        digitap = Mockito.mock(DigitapVerificationAdapter.class);
        // Most tests below exercise capabilities Fintrix doesn't offer at all, so leave it out of the
        // chain for those (see bureauXxx() below for the fintrix-specific chain).
        router = new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(List.of("signzy", "digitap"), null, null, null, null, null, null));
    }

    private RoutingVerificationPort bureauRouter() {
        return new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(
                        List.of("fintrix", "signzy", "digitap"), null, null, null, null, null, null));
    }

    /** The REAL production order — see application.yml and {@code effectiveChain()}. */
    private RoutingVerificationPort liveRouter() {
        return new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(
                        List.of("signzy", "fintrix", "digitap"), null, null, null, null, null, null));
    }

    private static PanCheck pan(String txn) {
        return new PanCheck(txn, txn, true, "NAME", null, null, true, null, "ABCPE1234Z", null, null,
                "operative", null, true, null);
    }

    @Test
    void primarySuccessWins_digitapNotCalled() {
        when(signzy.verifyPan(anyString(), anyString())).thenReturn(pan("SIGNZY"));

        PanCheck r = router.verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("SIGNZY");
        verify(digitap, never()).verifyPan(anyString(), anyString());
    }

    @Test
    void primaryFailureFallsThroughToDigitap() {
        when(signzy.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("signzy down"));
        when(digitap.verifyPan(anyString(), anyString())).thenReturn(pan("DIGITAP"));

        PanCheck r = router.verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("DIGITAP");
        verify(digitap).verifyPan(anyString(), anyString());
    }

    /**
     * {@code ProviderJson.post} now wraps a raw transport failure (read/connect timeout, connection
     * reset, an unreadable body) in a {@link VerificationException} rather than letting the underlying
     * {@code RestClientException} escape — this is what a provider adapter actually throws when its HTTP
     * call blows up below the response-envelope layer. Before that fix the raw exception propagated PAST
     * this router's {@code catch (VerificationException | CapabilityNotSupportedException)} and aborted
     * the whole chain (25 applications in the Sep-2026 pending-queue audit, Signzy leg 1, Digitap/Fintrix
     * never called). This pins that such a wrapped failure — httpStatus/providerCode both null, exactly
     * as {@code ProviderJson.post} constructs it — falls through like any other {@code VerificationException}.
     */
    @Test
    void transportFailureWrappedByProviderJsonFallsThroughToNextProvider() {
        VerificationException transportWrapped = new VerificationException(
                "Transport failure calling /pan", new java.net.SocketTimeoutException("Read timed out"),
                null, "/pan", null, null);
        when(signzy.verifyPan(anyString(), anyString())).thenThrow(transportWrapped);
        when(digitap.verifyPan(anyString(), anyString())).thenReturn(pan("DIGITAP"));

        PanCheck r = router.verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("DIGITAP");
        verify(digitap).verifyPan(anyString(), anyString());
    }

    /**
     * Fintrix serves PAN too, so the ONLY thing keeping it a fallback rather than the primary is that
     * signzy precedes it in the chain. The other PAN tests use a signzy,digitap chain and would not
     * notice if that order flipped — this one would.
     */
    @Test
    void panPrimaryIsSignzy_fintrixNeverCalledOnSuccess() {
        when(signzy.verifyPan(anyString(), anyString())).thenReturn(pan("SIGNZY"));

        PanCheck r = liveRouter().verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("SIGNZY");
        verify(fintrix, never()).verifyPan(anyString(), anyString());
        verify(digitap, never()).verifyPan(anyString(), anyString());
    }

    /** Signzy down → Fintrix serves, and Digitap is never reached. */
    @Test
    void panFallsThroughSignzyToFintrixBeforeDigitap() {
        when(signzy.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("signzy down"));
        when(fintrix.verifyPan(anyString(), anyString())).thenReturn(pan("FINTRIX"));

        PanCheck r = liveRouter().verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("FINTRIX");
        verify(digitap, never()).verifyPan(anyString(), anyString());
    }

    /** Both upstream providers down → Digitap is still the last resort. */
    @Test
    void panFallsAllTheWayToDigitapWhenSignzyAndFintrixFail() {
        when(signzy.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("signzy down"));
        when(fintrix.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("fintrix down"));
        when(digitap.verifyPan(anyString(), anyString())).thenReturn(pan("DIGITAP"));

        assertThat(liveRouter().verifyPan("ABCPE1234Z", "ref").txnId()).isEqualTo("DIGITAP");
    }

    @Test
    void livenessInitIsSignzyOnly_digitapNotCalled() {
        when(signzy.livenessInit(anyString(), anyString()))
                .thenReturn(new LivenessSession("TOK", "SIGNZY", "CID", "https://liveliness/x"));

        LivenessSession s = router.livenessInit("https://ref.jpg", "ref");

        assertThat(s.txnId()).isEqualTo("TOK");
        assertThat(s.videoUrl()).isEqualTo("https://liveliness/x");
        verify(digitap, never()).livenessInit(anyString(), anyString());
    }

    @Test
    void unsupportedByPrimarySkipsToDigitap() {
        when(signzy.verifyEmail(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException("Signzy has no email API"));
        when(digitap.verifyEmail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new EmailCheck("D", "DIGITAP", true, true, true, false, "ACME",
                        null, null, null, null, null, null, null, null, 0.9));

        EmailCheck r = router.verifyEmail("a@b.com", "John", "ACME", "ref");

        assertThat(r.txnId()).isEqualTo("D");
        verify(digitap).verifyEmail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void pennyDropIsSignzyOnly_digitapUnsupportedSurfacesLastError() {
        // Signzy tried and failed; Digitap doesn't offer penny-drop at all → router rethrows the
        // Signzy failure (the last meaningful error), not the "unsupported".
        when(signzy.pennyDrop(anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("bank upstream 500"));
        when(digitap.pennyDrop(anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException("Digitap has no penny-drop"));

        assertThatThrownBy(() -> router.pennyDrop("acct", "IFSC0001", "ref"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("bank upstream 500");
    }

    @Test
    void pennyDropPrimaryServes() {
        when(signzy.pennyDrop(anyString(), anyString(), anyString()))
                .thenReturn(new PennyDropCheck("PD", "SIGNZY", true, "RAVI KUMAR", "PNB", "IFSC0001",
                        "RRN1", "success", "yes"));

        PennyDropCheck r = router.pennyDrop("acct", "IFSC0001", "ref");

        assertThat(r.accountExists()).isTrue();
        verify(digitap, never()).pennyDrop(anyString(), anyString(), anyString());
    }

    // ---- Bureau: Fintrix (primary) -> Signzy (unsupported, retired) -> Digitap (fallback) ----

    private static BureauCheck bureau(String source, Integer score, boolean noRecord) {
        return new BureauCheck("TXN", source, score, noRecord, null, null, null, null, "{}");
    }

    @Test
    void bureauTriesFintrixFirst_signzyAndDigitapNotCalled() {
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", 799, false));

        BureauCheck r = bureauRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        verify(signzy, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(digitap, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bureauFintrixFailureFallsThroughToDigitap_signzySkippedSilently() {
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("fintrix down"));
        when(signzy.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException(
                        "Signzy bureau retired from routing — Fintrix is now primary"));
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", 700, false));

        BureauCheck r = bureauRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        verify(signzy).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(digitap).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bureauFintrixNoHitIsReturnedAsIs_doesNotFallThrough() {
        // A Fintrix no-hit is a real answer, not a failure — the router must return it rather than
        // trying Digitap next (the router already returns the first non-exception result; this guards
        // that behaviour for the specific noRecord=true case).
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", null, true));

        BureauCheck r = bureauRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        verify(digitap, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Signzy leading the live chain must NOT disturb bureau: its bureau leg is retired and skips itself,
     * so Fintrix stays the bureau primary exactly as it was before Fintrix also gained PAN. This is the
     * other half of the reorder's safety argument.
     */
    @Test
    void bureauPrimaryStaysFintrixUnderTheLiveChain() {
        when(signzy.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException(
                        "Signzy bureau retired from routing — Fintrix is now primary"));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", 780, false));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        verify(digitap, never())
                .pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bureauFintrixKbaChallengeIsReturnedAsIs_doesNotFallThroughToDigitap() {
        // A pending KBA challenge is a real answer (the report exists) wearing an error envelope —
        // exactly like the no-hit case above, it must come back as-is rather than falling through and
        // burning a second billable Digitap call. This is the test that pins the cost fix.
        PendingChallenge challenge = new PendingChallenge(
                "Please choose Disbursed Amount range for the latest Loan taken",
                List.of("0-5k", "5k-20k"), "txn-prod-b92e0254");
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BureauCheck("RID-1", "FINTRIX_CRIF", null, false, null, null, null, null,
                        null, null, challenge));

        BureauCheck r = bureauRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.pendingChallenge()).isNotNull();
        assertThat(r.pendingChallenge().orderId()).isEqualTo("txn-prod-b92e0254");
        assertThat(r.noRecord()).isFalse();
        verify(digitap, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
