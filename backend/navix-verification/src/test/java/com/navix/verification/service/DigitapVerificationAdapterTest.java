package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.VerificationPort.BureauCheck;
import com.navix.verification.client.DigitapAddressClient;
import com.navix.verification.client.DigitapCreditClient;
import com.navix.verification.client.DigitapCrifClient;
import com.navix.verification.client.DigitapEmailClient;
import com.navix.verification.client.DigitapFaceMatchClient;
import com.navix.verification.client.DigitapPanClient;
import com.navix.verification.client.DigitapUanClient;
import com.navix.verification.dto.DigitapDtos.CreditResponse;
import com.navix.verification.dto.DigitapDtos.CrifResponse;
import com.navix.verification.exception.VerificationException;
import org.junit.jupiter.api.Test;

/**
 * The two-step bureau path inside the Digitap adapter: CRIF first, Experian behind it. Both legs live
 * in one adapter because the router's provider list is global and maps each id to a single adapter —
 * so this is the only place the ordering can be pinned.
 */
class DigitapVerificationAdapterTest {

    private static final String CRIF_FLAG = "digitap-crif";

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
}
