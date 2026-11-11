package com.navix.loan.esigndoc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.loan.controller.SignzyContractWebhookController;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.service.OfferService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** A callback whose contract matches no borrower eSign row is handed to the admin document service. */
class SignzyWebhookRoutingTest {

    private final OfferService offer = mock(OfferService.class);
    private final ApplicationVerificationRepository repo = mock(ApplicationVerificationRepository.class);
    private final EsignDocumentService docs = mock(EsignDocumentService.class);
    private final SignzyContractWebhookController controller =
            new SignzyContractWebhookController(offer, repo, docs);

    SignzyWebhookRoutingTest() {
        ReflectionTestUtils.setField(controller, "callbackSecret", "s3cret");
    }

    @Test
    void unknownBorrowerContractRoutesToTheDocumentService() {
        when(repo.findByCheckTypeAndProviderTxnIdStartingWith(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("c-1"))).thenReturn(List.of());
        when(docs.onCallback("c-1")).thenReturn(true);

        var r = controller.contract("s3cret", Map.of("contractId", "c-1"));

        assertThat(r.getData()).containsEntry("accepted", true);
        verify(docs).onCallback("c-1");
        verify(offer, never()).esignStatus(anyLong());
    }

    @Test
    void contractKnownToNobodyIsNotAccepted() {
        when(repo.findByCheckTypeAndProviderTxnIdStartingWith(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.eq("x"))).thenReturn(List.of());
        when(docs.onCallback("x")).thenReturn(false);
        assertThat(controller.contract("s3cret", Map.of("contractId", "x")).getData()).containsEntry("accepted", false);
    }

    @Test
    void badSecretNeverReachesTheDocumentService() {
        assertThat(controller.contract("wrong", Map.of("contractId", "c-1")).getData()).containsEntry("accepted", false);
        verify(docs, never()).onCallback(org.mockito.ArgumentMatchers.anyString());
    }
}
