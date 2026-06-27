package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.AddressVerificationClient;
import com.navix.verification.client.DigiLockerClient;
import com.navix.verification.client.EmailVerificationClient;
import com.navix.verification.client.FaceLivenessClient;
import com.navix.verification.client.PanComprehensiveClient;
import com.navix.verification.client.PennyDropClient;
import com.navix.verification.dto.DigiLockerDtos;
import com.navix.verification.dto.FintrixDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link VerificationAdapter}: it is the single seam where Fintrix/DigiLocker
 * provider DTOs are mapped onto the provider-neutral {@link VerificationPort} records. Each case
 * feeds a provider envelope in and asserts the neutral record out (status flags, trimmed names,
 * nested-field projection, source/score passthrough).
 */
@ExtendWith(MockitoExtension.class)
class VerificationAdapterTest {

    @Mock private PanComprehensiveClient panClient;
    @Mock private EmailVerificationClient emailClient;
    @Mock private AddressVerificationClient addressClient;
    @Mock private PennyDropClient pennyDropClient;
    @Mock private FaceLivenessClient faceLivenessClient;
    @Mock private DigiLockerClient digiLockerClient;
    @Mock private BureauService bureauService;

    private VerificationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new VerificationAdapter(panClient, emailClient, addressClient, pennyDropClient,
                faceLivenessClient, digiLockerClient, bureauService);
    }

    @Test
    void verifyPan_mapsValidStatusAndTrimsName() {
        when(panClient.verify(anyString(), anyString())).thenReturn(new FintrixDtos.PanResponse(
                "TXN-PAN", "valid", "  SHUBHAM  ", "SHUBHAM", null, null, "2003-03-24", "M",
                "individual", true, "65XXXXXXXX90", "99XXXXXX99", "ab@xy.com", "QVEPS0901K",
                "Full address", "Haryana", "131001"));

        VerificationPort.PanCheck check = adapter.verifyPan("QVEPS0901K", "ref-1");

        assertThat(check.txnId()).isEqualTo("TXN-PAN");
        assertThat(check.valid()).isTrue();
        assertThat(check.fullName()).isEqualTo("SHUBHAM"); // trimmed
        assertThat(check.aadhaarLinked()).isTrue();
        assertThat(check.panNumber()).isEqualTo("QVEPS0901K");
        assertThat(check.addressState()).isEqualTo("Haryana");
        assertThat(check.addressZip()).isEqualTo("131001");
    }

    @Test
    void verifyPan_invalidStatus_isNotValid() {
        when(panClient.verify(anyString(), anyString())).thenReturn(new FintrixDtos.PanResponse(
                "TXN-PAN2", "invalid", "RAVI", null, null, null, null, null, null,
                null, null, null, null, "ABCDE1234F", null, null, null));

        VerificationPort.PanCheck check = adapter.verifyPan("ABCDE1234F", "ref-2");

        assertThat(check.valid()).isFalse();
        assertThat(check.aadhaarLinked()).isFalse(); // null Boolean → false
    }

    @Test
    void pennyDrop_projectsNestedIfscBankAndCode() {
        when(pennyDropClient.verify(anyString(), anyString(), anyString())).thenReturn(
                new FintrixDtos.PennyDropResponse("TXN-PD", true, true, "RAVI KUMAR",
                        new FintrixDtos.IfscDetails("HDFC Bank", "MG Road", "Pune", "MH", "HDFC0002557")));

        VerificationPort.PennyDropCheck check = adapter.pennyDrop("123456789", "HDFC0002557", "ref-3");

        assertThat(check.txnId()).isEqualTo("TXN-PD");
        assertThat(check.accountExists()).isTrue();
        assertThat(check.fullName()).isEqualTo("RAVI KUMAR");
        assertThat(check.bank()).isEqualTo("HDFC Bank");
        assertThat(check.ifsc()).isEqualTo("HDFC0002557");
    }

    @Test
    void pennyDrop_nullIfscDetails_yieldsNullBankAndIfsc() {
        when(pennyDropClient.verify(anyString(), anyString(), anyString())).thenReturn(
                new FintrixDtos.PennyDropResponse("TXN-PD2", false, false, null, null));

        VerificationPort.PennyDropCheck check = adapter.pennyDrop("000", "BAD", "ref-4");

        assertThat(check.accountExists()).isFalse();
        assertThat(check.bank()).isNull();
        assertThat(check.ifsc()).isNull();
    }

    @Test
    void pullBureau_passesThroughSourceAndScore() {
        when(bureauService.pull(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new BureauService.UnifiedBureauReport(
                        "TXN-BUR", "EXPERIAN", false, 780, 3, 0, 12345.0, 1));

        VerificationPort.BureauCheck check =
                adapter.pullBureau("QVEPS0901K", "SHUBHAM", "9819000001", "2003-03-24", "ref-5");

        assertThat(check.txnId()).isEqualTo("TXN-BUR");
        assertThat(check.source()).isEqualTo("EXPERIAN");
        assertThat(check.score()).isEqualTo(780);
        assertThat(check.noRecord()).isFalse();
        assertThat(check.activeAccounts()).isEqualTo(3);
        assertThat(check.overdueAccounts()).isEqualTo(0);
        assertThat(check.totalBalance()).isEqualTo(12345.0);
    }

    @Test
    void digilockerStatus_mapsStatusBooleans() {
        when(digiLockerClient.status(anyString())).thenReturn(new DigiLockerDtos.StatusResponse(
                "TXN-DL", "COMPLETED", true, false, true, null));

        VerificationPort.DigiLockerStatus status = adapter.digilockerStatus("client-1");

        assertThat(status.txnId()).isEqualTo("TXN-DL");
        assertThat(status.status()).isEqualTo("COMPLETED");
        assertThat(status.completed()).isTrue();
        assertThat(status.failed()).isFalse();
        assertThat(status.aadhaarLinked()).isTrue();
    }
}
