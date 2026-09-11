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
 * Verifies the {@link RoutingVerificationPort} chain semantics with mocked Signzy, Digitap (bureau
 * primary) and Fintrix (bureau fallback) adapters: primary success wins, a {@link VerificationException}
 * falls through to the fallback, a {@link CapabilityNotSupportedException} skips to the next provider,
 * and a capability no provider can serve rethrows.
 *
 * <p>Bureau additionally walks PAST a no-hit, so a thin file the leading bureau has never seen still
 * reaches the other one — see the {@code bureauXxx} tests and {@code noFallThroughRouter()}.
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
                new VerificationChainProperties(List.of("signzy", "digitap"), null, null, null, null, null,
                        null, null));
    }

    /** The REAL production order — see application.yml and {@code effectiveChain()}. */
    private RoutingVerificationPort liveRouter() {
        return new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(
                        List.of("signzy", "digitap", "fintrix"), null, null, null, null, null, null, null));
    }

    /** The live order with the bureau no-hit fall-through switched off — the pre-reorder semantics. */
    private RoutingVerificationPort noFallThroughRouter() {
        return new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(
                        List.of("signzy", "digitap", "fintrix"), null, null, null, null, null, null, false));
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

    /** Signzy down → Digitap serves, and Fintrix is never reached. */
    @Test
    void panFallsThroughSignzyToDigitapBeforeFintrix() {
        when(signzy.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("signzy down"));
        when(digitap.verifyPan(anyString(), anyString())).thenReturn(pan("DIGITAP"));

        PanCheck r = liveRouter().verifyPan("ABCPE1234Z", "ref");

        assertThat(r.txnId()).isEqualTo("DIGITAP");
        verify(fintrix, never()).verifyPan(anyString(), anyString());
    }

    /** Both upstream providers down → Fintrix is now the last resort. */
    @Test
    void panFallsAllTheWayToFintrixWhenSignzyAndDigitapFail() {
        when(signzy.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("signzy down"));
        when(digitap.verifyPan(anyString(), anyString()))
                .thenThrow(new VerificationException("digitap down"));
        when(fintrix.verifyPan(anyString(), anyString())).thenReturn(pan("FINTRIX"));

        assertThat(liveRouter().verifyPan("ABCPE1234Z", "ref").txnId()).isEqualTo("FINTRIX");
    }

    /**
     * The acceptance predicate that lets bureau walk past a no-hit must not leak into any other
     * capability: a PAN that simply comes back invalid is a real answer and terminates the chain.
     */
    @Test
    void nonBureauCapabilityIgnoresTheAcceptancePredicate() {
        PanCheck invalid = new PanCheck("SIGNZY", "SIGNZY", false, null, null, null, false, null,
                "ABCPE1234Z", null, null, "inoperative", null, null, null);
        when(signzy.verifyPan(anyString(), anyString())).thenReturn(invalid);

        assertThat(liveRouter().verifyPan("ABCPE1234Z", "ref").valid()).isFalse();
        verify(digitap, never()).verifyPan(anyString(), anyString());
        verify(fintrix, never()).verifyPan(anyString(), anyString());
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

    // ---- Bureau: Signzy (unsupported, retired) -> Digitap (primary) -> Fintrix (fallback) ----

    private static BureauCheck bureau(String source, Integer score, boolean noRecord) {
        return new BureauCheck("TXN", source, score, noRecord, null, null, null, null, "{}");
    }

    /** Signzy's retired bureau leg — it always skips itself, whatever else the chain is doing. */
    private void signzyBureauRetired() {
        when(signzy.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException(
                        "Signzy bureau retired from routing — Digitap is now primary"));
    }

    @Test
    void bureauTriesDigitapFirst_signzySkippedAndFintrixNotCalled() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", 799, false));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        verify(signzy).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(fintrix, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void bureauDigitapFailureFallsThroughToFintrix_signzySkippedSilently() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("digitap down"));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", 700, false));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        verify(signzy).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(fintrix).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * The reorder's whole safety argument: Experian having no file on a borrower must not end the pull,
     * because CRIF is a genuinely different data source that may well have one.
     */
    @Test
    void bureauDigitapNoHitFallsThroughToFintrixHit() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", null, true));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", 780, false));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        assertThat(r.score()).isEqualTo(780);
        assertThat(r.noRecord()).isFalse();
        verify(digitap).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(fintrix).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * Nobody has a file: still a real answer, and specifically the FIRST one. Returning the trailing
     * Fintrix no-hit instead would stamp every thin file FINTRIX_CRIF with a non-blank txn id, which is
     * exactly what {@code VerificationFailureService.discardedReport} reads as "a report we threw away —
     * offer a billable re-run". Keeping the chain head's answer also keeps bureauSource stable.
     */
    @Test
    void bureauBothNoHit_returnsTheFirstNoHitNotTheLast() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", null, true));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", null, true));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        assertThat(r.noRecord()).isTrue();
        verify(fintrix).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * An ANSWER beats the ABSENCE of one. We already hold a valid no-hit; discarding it to rethrow a
     * failure from a provider we only consulted as a bonus would leave the borrower worse off than
     * before the fall-through existed, when the no-hit simply terminated the chain.
     */
    @Test
    void bureauDigitapNoHitThenFintrixThrows_returnsTheNoHitRatherThanRethrowing() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", null, true));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("fintrix down"));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        assertThat(r.noRecord()).isTrue();
    }

    /** The mirror case: the primary fails outright and the fallback has no file — still not a throw. */
    @Test
    void bureauDigitapFailsAndFintrixNoHit_returnsFintrixNoHit() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("digitap down"));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("FINTRIX_CRIF", null, true));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        assertThat(r.noRecord()).isTrue();
    }

    /** Nothing answered at all → the real upstream failure surfaces, not Signzy's "unsupported" skip. */
    @Test
    void bureauBothProvidersFail_rethrowsTheRealFailureNotTheUnsupportedSkip() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("digitap down"));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("fintrix down"));

        assertThatThrownBy(() -> liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref"))
                .isInstanceOf(VerificationException.class)
                .hasMessageContaining("fintrix down");
    }

    /**
     * The property is the cost brake: with it off, a no-hit terminates the chain exactly as it did
     * before the reorder, so a thin file costs one billable pull rather than two.
     */
    @Test
    void bureauNoHitFallThroughDisabledByProperty_terminatesAtDigitap() {
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", null, true));

        BureauCheck r = noFallThroughRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        assertThat(r.noRecord()).isTrue();
        verify(fintrix, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * A pending KBA challenge is a real answer (the report exists) wearing an error envelope, so it must
     * come back as-is rather than being walked past by the no-hit fall-through and burning a second
     * billable pull. It reaches us only because Digitap found no file first — Fintrix is the sole issuer.
     */
    @Test
    void bureauFintrixKbaChallengeIsReturnedAsIs_afterADigitapNoHit() {
        PendingChallenge challenge = new PendingChallenge(
                "Please choose Disbursed Amount range for the latest Loan taken",
                List.of("0-5k", "5k-20k"), "txn-prod-b92e0254");
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", null, true));
        when(fintrix.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BureauCheck("RID-1", "FINTRIX_CRIF", null, false, null, null, null, null,
                        null, null, challenge));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.pendingChallenge()).isNotNull();
        assertThat(r.pendingChallenge().orderId()).isEqualTo("txn-prod-b92e0254");
        assertThat(r.noRecord()).isFalse();
    }

    /**
     * A challenge that arrived WITH noRecord set must still not be walked past — the report exists, and
     * losing it would strand an orderId nobody ever answers.
     */
    @Test
    void bureauChallengeIsAcceptableEvenWhenTheProviderAlsoFlagsNoRecord() {
        PendingChallenge challenge = new PendingChallenge("Q", List.of("a", "b"), "order-1", "report-1");
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BureauCheck("RID-2", "FINTRIX_CRIF", null, true, null, null, null, null,
                        null, null, challenge));

        BureauCheck r = liveRouter().pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref");

        assertThat(r.pendingChallenge()).isNotNull();
        verify(fintrix, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * An orderId belongs to the vendor that minted it, so answering deliberately bypasses the chain —
     * it must still go straight to Fintrix now that Fintrix sits LAST.
     */
    @Test
    void answerBureauChallengeBypassesTheChainEvenWithFintrixLast() {
        when(fintrix.answerBureauChallenge(anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString())).thenReturn(bureau("FINTRIX_CRIF", 742, false));

        BureauCheck r = liveRouter()
                .answerBureauChallenge("order-1", "report-1", "5k-20k", "Name", "9000000001", "ref");

        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
        verify(digitap, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
        verify(signzy, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    /**
     * The empty-chain safety net in the router's constructor is a second, independent copy of the
     * provider order — it has to agree with application.yml or a typo'd chain would silently restore
     * the old bureau primary.
     */
    @Test
    void unknownChainFallsBackToSignzyDigitapFintrixOrder() {
        RoutingVerificationPort bogus = new RoutingVerificationPort(fintrix, signzy, digitap,
                new VerificationChainProperties(
                        List.of("nope"), null, null, null, null, null, null, null));
        signzyBureauRetired();
        when(digitap.pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(bureau("DIGITAP_EXPERIAN", 710, false));

        assertThat(bogus.pullBureau("PAN", "Name", "9000000001", "1990-01-01", "", "ref").source())
                .isEqualTo("DIGITAP_EXPERIAN");
        verify(fintrix, never()).pullBureau(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }
}
