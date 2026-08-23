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
import com.navix.verification.client.FintrixCrifClient;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.exception.CapabilityNotSupportedException;
import org.junit.jupiter.api.Test;

/**
 * The {@code fintrix-bureau} kill switch: a present {@code enabled=false} row disables Fintrix (skip,
 * not a failure), an absent row leaves it enabled (defaultWhenMissing=true), and every non-bureau
 * capability is unsupported so the router always skips straight through.
 */
class FintrixVerificationAdapterTest {

    private final FintrixCrifClient crifClient = mock(FintrixCrifClient.class);
    private final FeatureFlagService featureFlags = mock(FeatureFlagService.class);
    private final FintrixVerificationAdapter adapter = new FintrixVerificationAdapter(crifClient, featureFlags);

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

    @Test
    void everyOtherCapabilityIsUnsupported() {
        assertThatThrownBy(() -> adapter.verifyPan("ABCPE1234Z", "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class);
        assertThatThrownBy(() -> adapter.pennyDrop("acct", "IFSC", "ref"))
                .isInstanceOf(CapabilityNotSupportedException.class);
        assertThatThrownBy(() -> adapter.digilockerInit("https://x", 30, true))
                .isInstanceOf(CapabilityNotSupportedException.class);
    }
}
