package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.verification.client.CrifClient;
import com.navix.verification.client.ExperianClient;
import com.navix.verification.dto.FintrixDtos.CrifResponse;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.service.BureauService.UnifiedBureauReport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Verifies the Experian PRIMARY &rarr; CRIF FALLBACK orchestration with mocked clients (no I/O).
 */
@ExtendWith(MockitoExtension.class)
class BureauServiceTest {

    @Mock
    private ExperianClient experianClient;
    @Mock
    private CrifClient crifClient;

    private CrifResponse crif(int score) {
        return new CrifResponse("TXN-CRIF", "success", "SUCCESS", score, 2, 0, 1000.0, 1);
    }

    @Test
    void usesExperianWhenScoredAndNotNoRecord() {
        BureauService service = new BureauService(experianClient, crifClient);
        when(experianClient.pull(any(), any(), any(), any()))
                .thenReturn(new ExperianResponse("TXN-EXP", "success", 760, false, "hit", null));

        UnifiedBureauReport report = service.pull("PAN", "Name", "9999999999", "1990-01-01", "ref");

        assertThat(report.score()).isEqualTo(760);
        verify(crifClient, never()).pull(any(), any(), any(), any(), any());
    }

    @Test
    void fallsBackToCrifOnExperianNoRecord() {
        BureauService service = new BureauService(experianClient, crifClient);
        when(experianClient.pull(any(), any(), any(), any()))
                .thenReturn(new ExperianResponse("TXN-EXP", "success", null, true, "No record found", null));
        when(crifClient.pull(any(), any(), any(), any(), any())).thenReturn(crif(700));

        UnifiedBureauReport report = service.pull("PAN", "Name", "9999999999", "1990-01-01", "ref");

        assertThat(report.score()).isEqualTo(700);
        assertThat(report.activeAccounts()).isEqualTo(2);
        verify(crifClient).pull(any(), any(), any(), any(), any());
    }

    @Test
    void fallsBackToCrifWhenExperianThrows() {
        BureauService service = new BureauService(experianClient, crifClient);
        when(experianClient.pull(any(), any(), any(), any()))
                .thenThrow(new VerificationException("HTTP 500 from individual_experian"));
        when(crifClient.pull(any(), any(), any(), any(), any())).thenReturn(crif(685));

        UnifiedBureauReport report = service.pull("PAN", "Name", "9999999999", "1990-01-01", "ref");

        assertThat(report.score()).isEqualTo(685);
        verify(crifClient).pull(any(), any(), any(), any(), any());
    }
}
