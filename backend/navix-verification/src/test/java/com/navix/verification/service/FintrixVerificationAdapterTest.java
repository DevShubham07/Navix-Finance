package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.VerificationPort.BureauCheck;
import com.navix.common.verification.VerificationPort.PanCheck;
import com.navix.verification.client.FintrixCrifClient;
import com.navix.verification.client.FintrixPanClient;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.dto.FintrixDtos.PanResponse;
import com.navix.verification.exception.CapabilityNotSupportedException;
import org.junit.jupiter.api.Test;

/**
 * The {@code fintrix-bureau} kill switch: a present {@code enabled=false} row disables Fintrix (skip,
 * not a failure), an absent row leaves it enabled (defaultWhenMissing=true). Also pins the PAN mapping
 * and that every capability Fintrix still doesn't serve stays unsupported, so the router skips through.
 */
class FintrixVerificationAdapterTest {

    private final FintrixCrifClient crifClient = mock(FintrixCrifClient.class);
    private final FintrixPanClient panClient = mock(FintrixPanClient.class);
    private final FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    private final FintrixVerificationAdapter adapter =
            new FintrixVerificationAdapter(crifClient, panClient, featureFlags);

    @Test
    void killSwitchOff_throwsCapabilityNotSupported_notVerificationException() {
        when(featureFlags.isEnabled(eq("fintrix-bureau"), anyBoolean())).thenReturn(false);

        assertThatThrownBy(() -> adapter.pullBureau("PAN", "Name", "9000000001", "1990-01-01", null, "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class);

        verify(crifClient, never()).pull(anyString(), anyString(), anyString());
    }

    @Test
    void flagRowAbsent_defaultsToEnabled_fintrixStillServes() {
        // FeatureFlagService itself returns the caller's default when no row exists (V31); the adapter
        // must pass true as that default so a fresh environment with no feature_flag row still gets the
        // primary bureau provider.
        when(featureFlags.isEnabled(eq("fintrix-bureau"), eq(true))).thenReturn(true);
        when(crifClient.pull("Name", "9000000001", "ref"))
                .thenReturn(new CrifResponse("TXN-1", 799, false, null, "{}", "https://link"));

        BureauCheck r = adapter.pullBureau("PAN", "Name", "9000000001", "1990-01-01", null, "ref");

        assertThat(r.score()).isEqualTo(799);
        assertThat(r.source()).isEqualTo("FINTRIX_CRIF");
    }

    /**
     * PAN is the one non-bureau capability Fintrix now serves. {@code status="valid"} is what makes
     * {@code PanCheck.valid} true, and the 206AB pair stays null — Fintrix has no equivalent.
     */
    @Test
    void verifyPanMapsIdentityFields_206abStaysNull() {
        when(panClient.verify(eq("QVEPS0901K"), anyString())).thenReturn(
                new PanResponse("TXN-PAN-1", "valid", "SHUBHAM", "2003-03-24", "M", true,
                        "65XXXXXXXX90", "QVEPS0901K", null, "Haryana", "131001"));

        PanCheck r = adapter.verifyPan("QVEPS0901K", "ref");

        assertThat(r.valid()).isTrue();
        assertThat(r.provider()).isEqualTo("FINTRIX");
        assertThat(r.txnId()).isEqualTo("TXN-PAN-1");
        assertThat(r.fullName()).isEqualTo("SHUBHAM");
        assertThat(r.dob()).isEqualTo("2003-03-24");
        assertThat(r.aadhaarLinked()).isTrue();
        assertThat(r.addressState()).isEqualTo("Haryana");
        assertThat(r.compliant()).isNull();
        assertThat(r.isSpecified()).isNull();
    }

    /** Anything but "valid" is a negative result, NOT an exception — the router must not fall through. */
    @Test
    void verifyPanNonValidStatusIsFalseNotAThrow() {
        when(panClient.verify(anyString(), anyString())).thenReturn(
                new PanResponse("TXN-PAN-2", "invalid", null, null, null, null, null,
                        "ABCPE1234Z", null, null, null));

        PanCheck r = adapter.verifyPan("ABCPE1234Z", "ref");

        assertThat(r.valid()).isFalse();
        assertThat(r.aadhaarLinked()).isFalse();
    }

    /** The bureau kill switch names the bureau only — it must never gate the PAN fallback. */
    @Test
    void bureauKillSwitchDoesNotDisablePan() {
        when(featureFlags.isEnabled(eq("fintrix-bureau"), anyBoolean())).thenReturn(false);
        when(panClient.verify(anyString(), anyString())).thenReturn(
                new PanResponse("TXN-PAN-3", "valid", "SHUBHAM", null, null, true, null,
                        "QVEPS0901K", null, null, null));

        assertThat(adapter.verifyPan("QVEPS0901K", "ref").valid()).isTrue();
    }

    @Test
    void everyOtherCapabilityIsUnsupported() {
        assertThatThrownBy(() -> adapter.pennyDrop("acct", "IFSC", "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class);
        assertThatThrownBy(() -> adapter.digilockerInit("https://x", 30, true))
                .isInstanceOf(CapabilityNotSupportedException.class);
    }
}
