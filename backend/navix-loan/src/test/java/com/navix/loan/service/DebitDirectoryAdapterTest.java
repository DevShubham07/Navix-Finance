package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.loan.entity.MandateDebit.Status;
import com.navix.loan.entity.MandateDebit.Trigger;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DebitDirectoryAdapterTest {

    @Test
    void manualPathAlwaysPresentsWithManualTriggerAndTheActor() {
        DebitService svc = mock(DebitService.class);
        when(svc.present(5L, 123_000L, Trigger.MANUAL, 42L)).thenReturn(new DebitService.DebitView(
                1L, 5L, Status.SCHEDULED, 123_000L, LocalDate.of(2026, 10, 18), 1, Trigger.MANUAL, null, false, false));
        var r = new DebitDirectoryAdapter(svc).presentForCase(5L, 123_000L, 42L);
        verify(svc).present(5L, 123_000L, Trigger.MANUAL, 42L);
        assertThat(r.status()).isEqualTo("SCHEDULED");
        assertThat(r.amountPaise()).isEqualTo(123_000L);
    }
}
