package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.verification.VerificationPort.EmailCheck;
import com.navix.common.verification.VerificationPort.PanCheck;
import com.navix.common.verification.VerificationPort.PennyDropCheck;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Verifies the {@link RoutingVerificationPort} chain semantics with a mocked Signzy (primary) and
 * Digitap (fallback) adapter: primary success wins, a {@link VerificationException} falls through to the
 * fallback, a {@link CapabilityNotSupportedException} skips to the next provider, and a capability no
 * provider can serve rethrows.
 */
class RoutingVerificationPortTest {

    private SignzyVerificationAdapter signzy;
    private DigitapVerificationAdapter digitap;
    private RoutingVerificationPort router;

    @BeforeEach
    void setUp() {
        signzy = Mockito.mock(SignzyVerificationAdapter.class);
        digitap = Mockito.mock(DigitapVerificationAdapter.class);
        router = new RoutingVerificationPort(signzy, digitap,
                new VerificationChainProperties(List.of("signzy", "digitap")));
    }

    private static PanCheck pan(String txn) {
        return new PanCheck(txn, txn, true, "NAME", null, null, true, null, "ABCPE1234Z", null, null);
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

    @Test
    void unsupportedByPrimarySkipsToDigitap() {
        when(signzy.verifyEmail(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new CapabilityNotSupportedException("Signzy has no email API"));
        when(digitap.verifyEmail(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new EmailCheck("D", "DIGITAP", true, true, true, false, "ACME"));

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
                .thenReturn(new PennyDropCheck("PD", "SIGNZY", true, "RAVI KUMAR", null, "IFSC0001"));

        PennyDropCheck r = router.pennyDrop("acct", "IFSC0001", "ref");

        assertThat(r.accountExists()).isTrue();
        verify(digitap, never()).pennyDrop(anyString(), anyString(), anyString());
    }
}
