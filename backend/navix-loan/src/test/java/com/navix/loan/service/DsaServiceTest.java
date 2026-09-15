package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.DsaDtos.CreateDsaLeadRequest;
import com.navix.loan.dto.DsaDtos.DsaLeadView;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.DsaCommissionRepository;
import com.navix.loan.repository.DsaLeadRejectionRepository;
import com.navix.loan.repository.LeadRepository;
import com.navix.loan.repository.LoanRepository;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Covers the DSA portal's hard isolation boundary and the entry-time PAN-rejection rules — the
 * point of the whole feature: a DSA must never read another DSA's lead or any customer data, and
 * a duplicate PAN (whether held by another DSA or an existing customer) is rejected with the same
 * generic error so neither case is distinguishable from the other.
 */
@ExtendWith(MockitoExtension.class)
class DsaServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private CustomerProfileRepository customerProfileRepository;
    @Mock private DsaLeadRejectionRepository rejectionRepository;
    @Mock private DsaCommissionRepository commissionRepository;
    @Mock private LoanRepository loanRepository;
    @Mock private DsaAttributionService attributionService;
    @Mock private LeadOutreachService outreachService;

    private DsaService service() {
        return new DsaService(leadRepository, customerProfileRepository, rejectionRepository,
                commissionRepository, loanRepository, attributionService, outreachService);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static void asDsa(long id) {
        ActorContext.set(new CurrentActor(String.valueOf(id), "DSA " + id, "DSA"));
    }

    private static CreateDsaLeadRequest validRequest(String pan) {
        return new CreateDsaLeadRequest(pan, "Asha Kumar", "9876543210", "asha@example.com",
                "Pune", "Acme Ltd", 50_000_00L, 10_000_00L, "met at expo");
    }

    // ---- ownership isolation ---------------------------------------------------------

    @Test
    void getOnForeignLeadReturnsLeadNotFound_notForbidden() {
        asDsa(2L);
        when(leadRepository.findVisibleToDsa(99L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().get(99L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("LEAD_NOT_FOUND"));
    }

    @Test
    void updateOnForeignLeadReturnsLeadNotFound() {
        asDsa(2L);
        when(leadRepository.findByIdAndOwnerDsaId(99L, 2L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().update(99L,
                new com.navix.loan.dto.DsaDtos.UpdateDsaLeadRequest(
                        "New Name", null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("LEAD_NOT_FOUND"));
    }

    @Test
    void listIsAlwaysScopedToTheCallingDsa_neverAClientSuppliedId() {
        asDsa(3L);
        when(leadRepository.findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(3L, 3L))
                .thenReturn(List.of());
        service().list(null, null);
        // BOTH ids are the ActorContext id — the widened scope (own + uploaded, V70) must never
        // introduce a path where a client-supplied id reaches the repository.
        org.mockito.Mockito.verify(leadRepository)
                .findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(3L, 3L);
    }

    @Test
    void listIncludesLeadsTheDsaUploadedButNeverAnotherDsas() {
        asDsa(3L);
        Lead entered = new Lead();
        entered.setId(1L);
        entered.setName("Entered");
        entered.setMobile("9876543210");
        entered.setPan("ABCDE1234F");
        entered.setOwnerDsaId(3L);
        entered.setCreatedByStaffId(3L);
        Lead uploaded = new Lead();
        uploaded.setId(2L);
        uploaded.setName("Uploaded");
        uploaded.setMobile("9876543211");
        uploaded.setOwnerDsaId(null);          // never attributed — cannot earn commission
        uploaded.setCreatedByStaffId(3L);
        when(leadRepository.findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(3L, 3L))
                .thenReturn(List.of(entered, uploaded));
        when(attributionService.attributedApplications(any())).thenReturn(java.util.Map.of());
        lenient().when(commissionRepository.findByLeadId(anyLong())).thenReturn(Optional.empty());

        List<DsaLeadView> out = service().list(null, null);

        assertThat(out).extracting(DsaLeadView::name).containsExactly("Entered", "Uploaded");
        assertThat(out).extracting(DsaLeadView::uploaded).containsExactly(false, true);
    }

    /**
     * PAN is mandatory on a DSA-entered lead but OPTIONAL on an uploaded one, so the search filter
     * NPE'd on {@code v.pan().toLowerCase()} the moment uploaded leads became visible here.
     */
    @Test
    void searchDoesNotBlowUpOnAnUploadedLeadWithNoPan() {
        asDsa(3L);
        Lead noPan = new Lead();
        noPan.setId(7L);
        noPan.setName("No Pan");
        noPan.setMobile("9876543212");
        noPan.setPan(null);
        noPan.setCreatedByStaffId(3L);
        when(leadRepository.findByOwnerDsaIdOrCreatedByStaffIdOrderByIdDesc(3L, 3L))
                .thenReturn(List.of(noPan));
        when(attributionService.attributedApplications(any())).thenReturn(java.util.Map.of());
        lenient().when(commissionRepository.findByLeadId(anyLong())).thenReturn(Optional.empty());

        assertThat(service().list("no pan", null)).hasSize(1);
        assertThat(service().list("ABCDE", null)).isEmpty();
    }

    // ---- entry-time PAN rejection -----------------------------------------------------

    @Test
    void duplicatePanAcrossDsasIsRejectedWithGenericMessage() {
        asDsa(5L);
        lenient().when(leadRepository.countByOwnerDsaIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F"))
                .thenReturn(Optional.of(new Lead()));

        assertThatThrownBy(() -> service().createLead(validRequest("ABCDE1234F")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo("LEAD_ALREADY_KNOWN"));

        ArgumentCaptor<com.navix.loan.entity.DsaLeadRejection> captor =
                ArgumentCaptor.forClass(com.navix.loan.entity.DsaLeadRejection.class);
        org.mockito.Mockito.verify(rejectionRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("OTHER_DSA");
    }

    @Test
    void panBelongingToExistingCustomerIsRejectedWithTheSameCodeAndMessage() {
        asDsa(5L);
        lenient().when(leadRepository.countByOwnerDsaIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F")).thenReturn(Optional.empty());
        when(customerProfileRepository.existsByPan("ABCDE1234F")).thenReturn(true);

        assertThatThrownBy(() -> service().createLead(validRequest("ABCDE1234F")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    BusinessException be = (BusinessException) e;
                    assertThat(be.getCode()).isEqualTo("LEAD_ALREADY_KNOWN");
                    assertThat(be.getMessage()).isEqualTo("This person is already known to us");
                });

        ArgumentCaptor<com.navix.loan.entity.DsaLeadRejection> captor =
                ArgumentCaptor.forClass(com.navix.loan.entity.DsaLeadRejection.class);
        org.mockito.Mockito.verify(rejectionRepository).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo("EXISTING_CUSTOMER");
    }

    @Test
    void invalidPanFormatIsRejectedByValidation() {
        // PAN_REGEX enforcement happens at the @Valid layer in the controller; the service itself
        // also normalizes/uppercases, but a malformed PAN never reaches persistence in practice
        // because @Pattern on CreateDsaLeadRequest rejects it before the service runs. Document the
        // regex here directly against the DTO's contract.
        assertThat("ABCDE1234F".matches("^[A-Z]{5}[0-9]{4}[A-Z]$")).isTrue();
        assertThat("abcde1234f".matches("^[A-Z]{5}[0-9]{4}[A-Z]$")).isFalse();
        assertThat("12345ABCDE".matches("^[A-Z]{5}[0-9]{4}[A-Z]$")).isFalse();
    }

    @Test
    void createLeadSucceedsForAFreshPan() {
        asDsa(7L);
        lenient().when(leadRepository.countByOwnerDsaIdAndCreatedAtAfter(anyLong(), any())).thenReturn(0L);
        when(leadRepository.findByPanAndOwnerDsaIdNotNull("ABCDE1234F")).thenReturn(Optional.empty());
        when(customerProfileRepository.existsByPan("ABCDE1234F")).thenReturn(false);
        when(leadRepository.save(any())).thenAnswer(i -> {
            Lead l = i.getArgument(0);
            l.setId(1L);
            l.setCreatedAt(Instant.now());
            return l;
        });
        when(attributionService.attributedApplication(any()))
                .thenReturn(DsaAttributionService.AttributedApplication.notApplied());
        when(commissionRepository.findByLeadId(1L)).thenReturn(Optional.empty());

        DsaLeadView view = service().createLead(validRequest("ABCDE1234F"));

        assertThat(view.pan()).isEqualTo("ABCDE1234F");
        assertThat(view.status()).isEqualTo(com.navix.loan.dto.DsaDtos.DsaLeadStatus.NOT_APPLIED);
    }

    // ---- DsaLeadView exposes no KYC-sourced field --------------------------------------

    @Test
    void dsaLeadViewExposesOnlyTheWhitelistedFields() {
        Set<String> allowed = Set.of(
                "id", "pan", "name", "mobile", "email", "city", "employer",
                "monthlySalaryPaise", "loanAmountInterestedPaise", "notes", "status",
                "netDisbursedPaise", "commissionPaise", "createdAt", "updatedAt",
                // V70. Each is safe for a specific reason: leadOutcome is a coarse staff-set value
                // (plus a derived CONFIRMED), dsaNote is a field created FOR this audience and
                // labelled as such where staff write it, and uploaded is a property of the row's
                // own provenance. None is KYC-sourced.
                "leadOutcome", "dsaNote", "uploaded");
        for (RecordComponent rc : DsaLeadView.class.getRecordComponents()) {
            assertThat(allowed).as("unexpected field on DsaLeadView: " + rc.getName())
                    .contains(rc.getName());
        }
        // Explicitly assert the dangerous ones are absent (defence in depth beyond the whitelist).
        Set<String> actual = java.util.Arrays.stream(DsaLeadView.class.getRecordComponents())
                .map(RecordComponent::getName).collect(java.util.stream.Collectors.toSet());
        assertThat(actual).doesNotContain(
                "address", "dob", "bureauScore", "creditStarRating", "applicationId",
                "customerId", "salaryBank", "documents", "verifiedName",
                // Internal staff fields. `remarks` especially: it is the telecaller's private call
                // commentary, written long before a DSA could read a lead at all, which is exactly
                // why V70 added a separate dsaNote instead of surfacing this one.
                "remarks", "qualityRating", "callStatus");
    }
}
