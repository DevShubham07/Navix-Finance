package com.navix.app.skiptrace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.ApplicationDtos.ApplicationView;
import com.navix.loan.dto.CustomerDtos.CustomerDetail;
import com.navix.loan.dto.LoanDtos.LoanView;
import com.navix.loan.dto.ReviewDtos.ProfileView;
import com.navix.loan.service.CustomerService;
import com.navix.verification.client.DigitapSkipTraceClient;
import com.navix.verification.dto.DigitapDtos.SkipTraceResponse;
import com.navix.verification.exception.VerificationException;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Who may spend money on a skip trace, what we send, and that every attempt leaves a row — the three
 * things that matter about a paid, PII-returning vendor call.
 */
@ExtendWith(MockitoExtension.class)
class SkipTraceServiceTest {

    private static final Long CUSTOMER = 3936960L;

    @Mock private SkipTraceRepository repository;
    @Mock private CustomerService customerService;
    @Mock private DigitapSkipTraceClient client;

    private SkipTraceService service;

    @BeforeEach
    void setUp() {
        service = new SkipTraceService(repository, customerService, client, new ObjectMapper());
        lenient().when(repository.save(any())).thenAnswer(i -> i.getArgument(0));
        CustomerDetail onFile = detail("EGSPR3662F", "7303936960"); // built before stubbing: Mockito must not see a half-finished when()
        lenient().when(customerService.detail(CUSTOMER)).thenReturn(onFile);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    private static void actingAs(String role) {
        ActorContext.set(new CurrentActor("12", "Head of Collections", role));
    }

    private static CustomerDetail detail(String pan, String mobile) {
        ProfileView profile = record(ProfileView.class, Map.of(
                "pan", pan == null ? "" : pan, "mobile", mobile == null ? "" : mobile,
                "fullName", "RINKU", "address", "A-36, Najafgarh Rd, New Delhi 110059"));
        ApplicationView older = record(ApplicationView.class, Map.of("id", 232L));
        ApplicationView newer = record(ApplicationView.class, Map.of("id", 10869L));
        LoanView loan = record(LoanView.class, Map.of("id", 18L));
        return new CustomerDetail(CUSTOMER, profile, List.of(older, newer), List.of(loan), List.of(),
                null, null, null, null, Map.of());
    }

    /** Build a wide DTO record with every component null except the named ones. */
    private static <T> T record(Class<T> type, Map<String, Object> values) {
        try {
            RecordComponent[] components = type.getRecordComponents();
            Class<?>[] types = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                types[i] = components[i].getType();
                Object v = values.get(components[i].getName());
                if (v == null && types[i].isPrimitive()) {
                    // Mixed-type ternaries unbox and NPE here, so spell the defaults out.
                    if (types[i] == boolean.class) v = Boolean.FALSE;
                    else if (types[i] == int.class) v = 0;
                    else if (types[i] == long.class) v = 0L;
                    else if (types[i] == double.class) v = 0.0d;
                }
                args[i] = v;
            }
            return type.getDeclaredConstructor(types).newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SkipTraceResponse found() {
        return new SkipTraceResponse("REQ-ST-1", 101, "Skip tracing successful",
                "{\"result_code\":101,\"result\":{\"addresses\":[{\"address_rank\":1}]}}");
    }

    @Test
    void aCollectionExecutiveCannotRunIt() {
        actingAs("COLLECTION_EXECUTIVE");
        assertThatThrownBy(() -> service.run(CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("COLLECTION_HEAD");
        verifyNoInteractions(client, repository);
    }

    @Test
    void aCreditHeadCannotRunIt() {
        actingAs("CREDIT_HEAD");
        assertThatThrownBy(() -> service.run(CUSTOMER)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(client, repository);
    }

    @Test
    void theCollectionHeadRunsItAndEverythingOnFileIsSent() {
        actingAs("COLLECTION_HEAD");
        when(client.trace(eq("EGSPR3662F"), eq("7303936960"), eq("RINKU"),
                eq(List.of("A-36, Najafgarh Rd, New Delhi 110059")), eq("navix-10869-SKIP_TRACE")))
                .thenReturn(found());

        SkipTraceService.SkipTraceView v = service.run(CUSTOMER);

        assertThat(v.status()).isEqualTo(SkipTrace.SUCCESS);
        assertThat(v.resultCode()).isEqualTo(101);
        assertThat(v.providerRequestId()).isEqualTo("REQ-ST-1");
        assertThat(v.applicationId()).isEqualTo(10869L); // the latest file, not the first
        assertThat(v.loanId()).isEqualTo(18L);
        assertThat(v.runByStaffId()).isEqualTo(12L);
        assertThat(v.runByRole()).isEqualTo("COLLECTION_HEAD");
        assertThat(v.request()).containsEntry("pan", "EGSPR3662F").containsEntry("mobile", "7303936960");
        assertThat(v.response()).isInstanceOf(Map.class);
    }

    @Test
    void adminBypassesTheRoleCheck() {
        actingAs("ADMIN");
        when(client.trace(anyString(), anyString(), anyString(), any(), anyString())).thenReturn(found());

        assertThat(service.run(CUSTOMER).status()).isEqualTo(SkipTrace.SUCCESS);
    }

    @Test
    void noRecordIsARecordedOutcomeNotAnError() {
        actingAs("ADMIN");
        when(client.trace(anyString(), anyString(), anyString(), any(), anyString()))
                .thenReturn(new SkipTraceResponse("REQ-ST-2", 103, "No record(s) found", "{\"result_code\":103}"));

        SkipTraceService.SkipTraceView v = service.run(CUSTOMER);

        assertThat(v.status()).isEqualTo(SkipTrace.NO_RECORD);
        assertThat(v.message()).isEqualTo("No record(s) found");
    }

    /** The attempt is kept even when Digitap fails, so staff can see it was tried and by whom. */
    @Test
    void aProviderFailureIsSavedAsFailedAndSurfacedAsBusinessError() {
        actingAs("ADMIN");
        when(client.trace(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new VerificationException("HTTP 500 from /enrichment/misc/v1/skip-tracing-lite"));

        assertThatThrownBy(() -> service.run(CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("could not complete");

        ArgumentCaptor<SkipTrace> saved = ArgumentCaptor.forClass(SkipTrace.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(SkipTrace.FAILED);
        assertThat(saved.getValue().getMessage()).contains("HTTP 500");
        assertThat(saved.getValue().getResponseJson()).isNull();
    }

    @Test
    void nothingToTraceIsRefusedBeforeAnyCall() {
        actingAs("ADMIN");
        CustomerDetail nothingOnFile = detail("", null);
        when(customerService.detail(CUSTOMER)).thenReturn(nothingOnFile);

        assertThatThrownBy(() -> service.run(CUSTOMER))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("neither a PAN nor a mobile");
        verifyNoInteractions(client);
        verify(repository, never()).save(any());
    }

    /** Reading goes through the customer page's own visibility rule — a DSA is rejected there. */
    @Test
    void historyUsesTheCustomerPagesVisibility() {
        actingAs("DSA");
        when(customerService.detail(CUSTOMER))
                .thenThrow(new BusinessException("FORBIDDEN_ROLE", "DSAs cannot view customer data"));

        assertThatThrownBy(() -> service.history(CUSTOMER)).hasMessageContaining("DSA");
        verify(repository, never()).findByCustomerIdOrderByCreatedAtDesc(any());
    }

    @Test
    void historyIsReadableByAnyOtherStaffWithoutAProviderCall() {
        actingAs("COLLECTION_EXECUTIVE");
        when(repository.findByCustomerIdOrderByCreatedAtDesc(CUSTOMER)).thenReturn(List.of());

        assertThat(service.history(CUSTOMER)).isEmpty();
        verifyNoInteractions(client);
    }
}
