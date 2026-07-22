package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.iam.dto.PaymentDtos.PaymentSettingsResponse;
import com.navix.iam.dto.PaymentDtos.UpdatePaymentSettingsRequest;
import com.navix.iam.entity.PaymentSettings;
import com.navix.iam.repository.PaymentSettingsRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentSettingsServiceTest {

    @Mock
    private PaymentSettingsRepository repository;

    @Mock
    private DocumentStoragePort storage;

    private PaymentSettingsService service;

    @BeforeEach
    void setUp() {
        service = new PaymentSettingsService(repository, storage);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static PaymentSettings seeded() {
        PaymentSettings s = new PaymentSettings();
        s.setUpiId("navix.collections@hdfcbank");
        s.setAccountName("DhanBoost");
        s.setAccountNumber("5010 0099 8877");
        s.setIfsc("HDFC0000123");
        s.setBankName("HDFC Bank");
        return s;
    }

    @Test
    void getReturnsSettingsWithNullUrlsWhenNoAssets() {
        when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(seeded()));

        PaymentSettingsResponse res = service.get();

        assertThat(res.upiId()).isEqualTo("navix.collections@hdfcbank");
        assertThat(res.accountNumber()).isEqualTo("5010 0099 8877");
        assertThat(res.qrUrl()).isNull();
        assertThat(res.accountInfoUrl()).isNull();
        verify(storage, never()).presignDownload(any());
    }

    @Test
    void getPresignsStoredAssetKeys() {
        PaymentSettings s = seeded();
        s.setQrObjectKey("payment/settings/qr.jpg");
        s.setAccountInfoObjectKey("payment/settings/info.pdf");
        when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(s));
        when(storage.presignDownload("payment/settings/qr.jpg")).thenReturn("https://s3/qr");
        when(storage.presignDownload("payment/settings/info.pdf")).thenReturn("https://s3/info");

        PaymentSettingsResponse res = service.get();

        assertThat(res.qrUrl()).isEqualTo("https://s3/qr");
        assertThat(res.accountInfoUrl()).isEqualTo("https://s3/info");
    }

    @Test
    void updateByAdminAppliesNonNullFields() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        PaymentSettings s = seeded();
        when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(s));
        when(repository.save(any())).thenAnswer(i -> i.getArgument(0));

        PaymentSettingsResponse res = service.update(new UpdatePaymentSettingsRequest(
                "new.upi@bank", null, null, null, null, "payment/settings/qr.jpg", null));

        assertThat(res.upiId()).isEqualTo("new.upi@bank");
        // untouched field retained
        assertThat(res.bankName()).isEqualTo("HDFC Bank");
        assertThat(s.getQrObjectKey()).isEqualTo("payment/settings/qr.jpg");
    }

    @Test
    void updateByNonAdminThrowsForbidden() {
        ActorContext.set(new CurrentActor("9", "Bob", "ACCOUNTANT"));
        lenient().when(repository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(seeded()));

        assertThatThrownBy(() -> service.update(new UpdatePaymentSettingsRequest(
                "hacked@bank", null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("admin");
        verify(repository, never()).save(any());
    }
}
