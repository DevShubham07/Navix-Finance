package com.navix.collections.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.navix.common.storage.DocumentStoragePort;
import org.junit.jupiter.api.Test;

class PresignProofTest {

    @Test
    void onlyAnUploadedRepaymentProofIsPresigned() {
        DocumentStoragePort storage = mock(DocumentStoragePort.class);
        when(storage.presignDownload("loan/repayment-proof/abc-shot.png")).thenReturn("https://signed");

        assertThat(CollectionsDtos.presignProof(storage, "loan/repayment-proof/abc-shot.png")).isEqualTo("https://signed");
        for (String notAKey : new String[] {null, "UTR-123", "kyc/document/x.png",
                "loan/repayment-proof/../../kyc/document/x.png"}) {
            assertThat(CollectionsDtos.presignProof(storage, notAKey)).as(String.valueOf(notAKey)).isNull();
        }
    }
}
