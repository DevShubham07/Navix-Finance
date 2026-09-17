package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.VerificationPort.BureauCheck;
import com.navix.common.verification.VerificationPort.EmailCheck;
import com.navix.common.verification.VerificationPort.PanCheck;
import com.navix.verification.client.DigitapAddressClient;
import com.navix.verification.client.DigitapCreditClient;
import com.navix.verification.client.DigitapCrifClient;
import com.navix.verification.client.DigitapEmailClient;
import com.navix.verification.client.DigitapFaceMatchClient;
import com.navix.verification.client.DigitapPanClient;
import com.navix.verification.client.DigitapUanClient;
import com.navix.verification.dto.DigitapDtos.CreditResponse;
import com.navix.verification.dto.DigitapDtos.CrifResponse;
import com.navix.verification.dto.DigitapDtos.EmailResponse;
import com.navix.verification.dto.DigitapDtos.PanResponse;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;

/**
 * The two-step bureau path inside the Digitap adapter: CRIF first, Experian behind it. Both legs live
 * in one adapter because the router's provider list is global and maps each id to a single adapter —
 * so this is the only place the ordering can be pinned.
 *
 * <p>Also the flag guards on the two Digitap products that are not provisioned on this account. PAN
 * Details Plus and Email Verification v1 answered {@code 412 Precondition Failed} to every call in the
 * Sep-2026 audit — 96 out of 96 and 39 out of 39, zero successes ever — so each borrower whose Signzy
 * leg failed paid a guaranteed-dead round trip before the real fallback ran.
 */
class DigitapVerificationAdapterTest {

    private static final String CRIF_FLAG = "digitap-crif";
    private static final String PAN_FLAG = "digitap-pan";
    private static final String EMAIL_FLAG = "digitap-email";

    private final DigitapPanClient panClient = mock(DigitapPanClient.class);
    private final DigitapEmailClient emailClient = mock(DigitapEmailClient.class);
    private final DigitapAddressClient addressClient = mock(DigitapAddressClient.class);
    private final DigitapCrifClient crifClient = mock(DigitapCrifClient.class);
    private final DigitapCreditClient creditClient = mock(DigitapCreditClient.class);
    private final DigitapFaceMatchClient faceMatchClient = mock(DigitapFaceMatchClient.class);
    private final DigitapUanClient uanClient = mock(DigitapUanClient.class);
    private final FeatureFlagService featureFlags = mock(FeatureFlagService.class);

    private final DigitapVerificationAdapter adapter = new DigitapVerificationAdapter(
            panClient, emailClient, addressClient, crifClient, creditClient,
            faceMatchClient, uanClient, featureFlags);

    private void crifEnabled(boolean on) {
        when(featureFlags.isEnabled(eq(CRIF_FLAG), anyBoolean())).thenReturn(on);
    }

    private BureauCheck pull() {
        return adapter.pullBureau("ABCPE1234Z", "John Doe", "9000000001", "01-01-1990", "", "ref");
    }

    /**
     * The flag is read with defaultWhenMissing=FALSE — the opposite of {@code fintrix-bureau} — because
     * the endpoint has not authenticated yet. A fresh environment with no feature_flag row must behave
     * exactly as it did before this leg existed: Experian only.
     */
    @Test
    void flagRowAbsent_defaultsToDisabled_crifNeverCalled() {
        when(featureFlags.isEnabled(eq(CRIF_FLAG), eq(false))).thenReturn(false);
        when(creditClient.pull(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CreditResponse("TXN-EXP", 700, false, null, "{}"));

        assertThat(pull().source()).isEqualTo("DIGITAP_EXPERIAN");
        verify(crifClient, never()).pull(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void crifServes_experianNeverCalled() {
        crifEnabled(true);
        when(crifClient.pull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CrifResponse("TXN-CRIF", 510, false, "{}"));

        BureauCheck r = pull();

        assertThat(r.source()).isEqualTo("DIGITAP_CRIF");
        assertThat(r.score()).isEqualTo(510);
        verify(creditClient, never())
                .pull(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void crifFailureFallsThroughToExperian() {
        crifEnabled(true);
        when(crifClient.pull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new VerificationException("digitap crif down"));
        when(creditClient.pull(anyString(), anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CreditResponse("TXN-EXP", 700, false, null, "{}"));

        BureauCheck r = pull();

        assertThat(r.source()).isEqualTo("DIGITAP_EXPERIAN");
        assertThat(r.score()).isEqualTo(700);
    }

    /**
     * THE COST GUARD. A CRIF no-hit is a real answer, so it must be returned as-is. Falling through
     * would spend a second billable Experian pull on every thin-file borrower — the same rule
     * {@code RoutingVerificationPortTest.bureauFintrixNoHitIsReturnedAsIs_doesNotFallThrough} pins one
     * level up.
     */
    @Test
    void crifNoRecordIsReturnedAsIs_experianNotCalled() {
        crifEnabled(true);
        when(crifClient.pull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new CrifResponse("TXN-CRIF", null, true, "{}"));

        BureauCheck r = pull();

        assertThat(r.source()).isEqualTo("DIGITAP_CRIF");
        assertThat(r.noRecord()).isTrue();
        assertThat(r.score()).isNull();
        verify(creditClient, never())
                .pull(anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    // ---- The two unprovisioned products: PAN Details Plus and Email Verification v1 ----

    /**
     * No feature_flag row is the shipping state, and it must keep the dead leg out of the chain. The
     * throw has to be {@link CapabilityNotSupportedException} specifically — a plain failure would be
     * remembered by the router as "the actionable error" and surfaced to staff ahead of whatever
     * Signzy or Fintrix actually said, turning an unprovisioned product into the reported cause of a
     * PAN check that never had a chance.
     *
     * <p>The client must not be touched at all: the whole point is not spending the 412.
     */
    @Test
    void panIsUnsupportedWhileTheFlagIsOff() {
        assertThatThrownBy(() -> adapter.verifyPan("ABCPE1234Z", "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class)
                .hasMessageContaining(PAN_FLAG);

        verifyNoInteractions(panClient);
    }

    /**
     * The other half of the switch: the day Digitap provisions the product, an {@code enabled=true} row
     * restores the leg with no redeploy. Stubbing on {@code eq(false)} also pins the defaultWhenMissing
     * the adapter reads with — if it ever asked for {@code true}, this stub would miss and the call
     * would come back unsupported.
     */
    @Test
    void panDelegatesWhenTheFlagIsOn() {
        when(featureFlags.isEnabled(eq(PAN_FLAG), eq(false))).thenReturn(true);
        when(panClient.verify("ABCPE1234Z", "ref")).thenReturn(new PanResponse(
                "TXN-PAN", true, "  JOHN DOE  ", "JOHN", "DOE", "1990-01-01", "M", true,
                "operative", "DL", "110001"));

        PanCheck r = adapter.verifyPan("ABCPE1234Z", "ref");

        assertThat(r.provider()).isEqualTo("DIGITAP");
        assertThat(r.valid()).isTrue();
        assertThat(r.fullName()).isEqualTo("JOHN DOE");
        verify(panClient).verify("ABCPE1234Z", "ref");
    }

    /**
     * Email is the same story as PAN and fails the same way — 39 calls, 39 × 412, no success on record
     * — but it matters more: email is a real intake gate, so a dead fallback leg here is a borrower
     * stuck on the {@code email} step rather than a check quietly downgraded to REVIEW.
     */
    @Test
    void emailIsUnsupportedWhileTheFlagIsOff() {
        assertThatThrownBy(() -> adapter.verifyEmail("a@b.com", "John Doe", "ACME", "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class)
                .hasMessageContaining(EMAIL_FLAG);

        verifyNoInteractions(emailClient);
    }

    /** As {@link #panDelegatesWhenTheFlagIsOn}: the row is the only thing standing between us and the leg. */
    @Test
    void emailDelegatesWhenTheFlagIsOn() {
        when(featureFlags.isEnabled(eq(EMAIL_FLAG), eq(false))).thenReturn(true);
        when(emailClient.verify("a@b.com", "John Doe", "ACME", "ref")).thenReturn(new EmailResponse(
                "TXN-EMAIL", 101, true, true, true, true, false, "ACME PVT LTD", 0.92));

        EmailCheck r = adapter.verifyEmail("a@b.com", "John Doe", "ACME", "ref");

        assertThat(r.provider()).isEqualTo("DIGITAP");
        assertThat(r.verified()).isTrue();
        assertThat(r.matchedEstablishment()).isEqualTo("ACME PVT LTD");
        verify(emailClient).verify("a@b.com", "John Doe", "ACME", "ref");
    }
}
