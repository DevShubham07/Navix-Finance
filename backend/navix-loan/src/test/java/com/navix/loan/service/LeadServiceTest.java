package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
import com.navix.loan.dto.LeadDtos.LeadOutcomeRequest;
import com.navix.loan.dto.LeadDtos.LeadPage;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private StaffDirectory staffDirectory;
    @Mock private JdbcTemplate jdbc;
    @Mock private DsaAttributionService attributionService;
    @Mock private LeadBookQuery bookQuery;

    private LeadService service;

    @BeforeEach
    void setUp() {
        service = new LeadService(leadRepository, staffDirectory, attributionService, jdbc, bookQuery);
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void create_asTelecaller_persistsRequiredFields() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        when(staffDirectory.findStaff(42L)).thenReturn(Optional.of(
                new StaffSummary(42L, "Tara", "TELECALLER", true)));
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> {
            Lead l = inv.getArgument(0);
            l.setId(1L);
            return l;
        });

        LeadView view = service.create(new CreateLeadRequest(
                "Ravi Kumar", "9876543210", null, "Delhi", null, null, null,
                "DSA", "Acme DSA", null));

        assertThat(view.name()).isEqualTo("Ravi Kumar");
        assertThat(view.mobile()).isEqualTo("9876543210");
        assertThat(view.callStatus()).isEqualTo("NOT_CALLED");
        assertThat(view.source()).isEqualTo("DSA");
        assertThat(view.createdByStaffId()).isEqualTo(42L);

        ArgumentCaptor<Lead> cap = ArgumentCaptor.forClass(Lead.class);
        verify(leadRepository).save(cap.capture());
        assertThat(cap.getValue().getCity()).isEqualTo("Delhi");
    }

    @Test
    void create_forbiddenForNonTelecaller() {
        ActorContext.set(new CurrentActor("7", "Exec", "CREDIT_EXECUTIVE"));
        assertThatThrownBy(() -> service.create(new CreateLeadRequest(
                "X", "9876543210", null, null, null, null, null, null, null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("FORBIDDEN_ROLE");
    }

    // ---- outcome + DSA note (V70) -------------------------------------------------------

    @Test
    void outcome_rejectsAValueOutsideTheVocabulary() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        assertThatThrownBy(() -> service.outcome(9L, new LeadOutcomeRequest("BOGUS", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_LEAD_OUTCOME");
    }

    /** CONFIRMED is derived from the attributed application on read — it must not be settable. */
    @Test
    void outcome_rejectsConfirmedBecauseItIsDerivedNotStored() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        assertThatThrownBy(() -> service.outcome(9L, new LeadOutcomeRequest("CONFIRMED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_LEAD_OUTCOME");
    }

    /**
     * Patch, not replace. {@code disposition()} overwrites remarks/rating from the request, so if
     * these two fields lived there the telecaller panel would null them on every save.
     */
    @Test
    void outcome_leavesAnUnsentFieldAlone() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        Lead existing = new Lead();
        existing.setId(9L);
        existing.setName("Ravi");
        existing.setCreatedByStaffId(1L);
        existing.setLeadOutcome("OUTREACHED");
        existing.setDsaNote("keep me");
        when(leadRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));

        service.outcome(9L, new LeadOutcomeRequest("REJECTED", null));

        assertThat(existing.getLeadOutcome()).isEqualTo("REJECTED");
        assertThat(existing.getDsaNote()).isEqualTo("keep me");

        service.outcome(9L, new LeadOutcomeRequest(null, "  now with a note  "));

        assertThat(existing.getLeadOutcome()).isEqualTo("REJECTED");
        assertThat(existing.getDsaNote()).isEqualTo("now with a note");
    }

    /**
     * {@code requireLead} is a bare findById with no ownership predicate, while {@code list()}
     * filters {@code ownerDsaId IS NULL} — so a telecaller cannot FIND a DSA-owned lead but could
     * write to one by id. The outcome path closes that rather than inheriting it.
     */
    @Test
    void outcome_refusesADsaOwnedLead() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        Lead dsaOwned = new Lead();
        dsaOwned.setId(9L);
        dsaOwned.setOwnerDsaId(42L);
        dsaOwned.setCreatedByStaffId(42L);
        when(leadRepository.findById(9L)).thenReturn(Optional.of(dsaOwned));

        assertThatThrownBy(() -> service.outcome(9L, new LeadOutcomeRequest("REJECTED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("LEAD_NOT_FOUND");
        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).save(any(Lead.class));
    }

    @Test
    void getAndDisposition_refuseADsaOwnedLead_likeOutcomeDoes() {
        ActorContext.set(new CurrentActor("5", "Tara", "TELECALLER"));
        Lead dsaOwned = new Lead();
        dsaOwned.setId(9L);
        dsaOwned.setOwnerDsaId(42L);
        dsaOwned.setCreatedByStaffId(42L);
        when(leadRepository.findById(9L)).thenReturn(Optional.of(dsaOwned));

        assertThatThrownBy(() -> service.get(9L))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("LEAD_NOT_FOUND");
        assertThatThrownBy(() -> service.disposition(9L, new DispositionRequest("CONNECTED", null, null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("LEAD_NOT_FOUND");
        org.mockito.Mockito.verify(leadRepository, org.mockito.Mockito.never()).save(any(Lead.class));
    }

    @Test
    void outcome_forbiddenForNonTelecaller() {
        ActorContext.set(new CurrentActor("1", "Agent", "DSA"));

        assertThatThrownBy(() -> service.outcome(9L, new LeadOutcomeRequest("REJECTED", null)))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("FORBIDDEN_ROLE");
    }

    @Test
    void disposition_rejectsBadStatus() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        assertThatThrownBy(() -> service.disposition(9L,
                new DispositionRequest("BOGUS", 3, "hi")))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("INVALID_CALL_STATUS");
    }

    @Test
    void disposition_updatesStatusAndStars() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        Lead existing = new Lead();
        existing.setId(9L);
        existing.setName("A");
        existing.setMobile("9876543210");
        existing.setCallStatus("NOT_CALLED");
        existing.setCreatedByStaffId(1L);
        when(leadRepository.findById(9L)).thenReturn(Optional.of(existing));
        when(leadRepository.save(any(Lead.class))).thenAnswer(inv -> inv.getArgument(0));
        when(staffDirectory.findStaff(1L)).thenReturn(Optional.of(
                new StaffSummary(1L, "Admin", "ADMIN", true)));

        LeadView view = service.disposition(9L,
                new DispositionRequest("CALLED", 4, "Interested in ₹10k"));

        assertThat(view.callStatus()).isEqualTo("CALLED");
        assertThat(view.qualityRating()).isEqualTo(4);
        assertThat(view.remarks()).isEqualTo("Interested in ₹10k");
    }

    @Test
    void stats_requiresAdmin() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        assertThatThrownBy(() -> service.stats(null, null, null))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("FORBIDDEN_ROLE");
    }

    private LeadBookQuery.LeadFilter listFiltered(String q, String status, String source, Long by,
            LocalDate from, LocalDate to, Integer min, Integer max, String outcome) {
        when(bookQuery.count(any(), any())).thenReturn(0L);
        when(bookQuery.pageIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());
        service.list(q, status, source, by, from, to, min, max, outcome, 1, 25);
        ArgumentCaptor<LeadBookQuery.LeadFilter> cap = ArgumentCaptor.forClass(LeadBookQuery.LeadFilter.class);
        verify(bookQuery).count(cap.capture(), any());
        return cap.getValue();
    }

    /** The staff-only gate stays exactly as it was: the query is never reached by another role. */
    @Test
    void list_isForbiddenForNonLeadWriters() {
        ActorContext.set(new CurrentActor("7", "Exec", "CREDIT_EXECUTIVE"));
        assertThatThrownBy(() -> service.list(null, null, null, null, null, null, null, null, null, 1, 25))
                .isInstanceOf(BusinessException.class)
                .extracting(ex -> ((BusinessException) ex).getCode())
                .isEqualTo("FORBIDDEN_ROLE");
        assertThatThrownBy(() -> service.facets("source", null, null, null, null, null, null, null, null, null,
                java.util.Map.of()))
                .isInstanceOf(BusinessException.class);
        org.mockito.Mockito.verifyNoInteractions(bookQuery);
    }

    /** The window is half-open UTC: [from 00:00, day-after-to 00:00) — the boundary the Specification used. */
    @Test
    void list_withFromAndToOnly_buildsExclusiveUtcDateRange() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));

        LeadBookQuery.LeadFilter f = listFiltered(null, null, null, null,
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 8, 12), null, null, null);

        assertThat(f.from()).isEqualTo(Instant.parse("2026-07-13T00:00:00Z"));
        assertThat(f.to()).isEqualTo(Instant.parse("2026-08-13T00:00:00Z"));
    }

    @Test
    void list_normalisesTrimmingAndOutcomeCase() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        LeadBookQuery.LeadFilter f = listFiltered("  Ravi  ", " CALLBACK ", " DSA ", 42L, null, null, 2, 4, " new ");

        assertThat(f).isEqualTo(new LeadBookQuery.LeadFilter(
                "Ravi", "CALLBACK", "DSA", 42L, null, null, 2, 4, "NEW"));
    }

    @Test
    void list_blankParamsAreNoFilter() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));

        LeadBookQuery.LeadFilter f = listFiltered("  ", "", " ", null, null, null, null, null, "");

        assertThat(f).isEqualTo(new LeadBookQuery.LeadFilter(null, null, null, null, null, null, null, null, null));
    }

    /** Only the ids on the page are loaded, in the QUERY's order (findAllById promises none). */
    @Test
    void list_pagesAndReportsTheTotalAcrossTheWholeFilter() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        when(bookQuery.count(any(), any())).thenReturn(5L);
        when(bookQuery.pageIds(any(), any(), eq(2), eq(2))).thenReturn(List.of(11L, 10L));
        when(leadRepository.findAllById(List.of(11L, 10L)))
                .thenReturn(List.of(lead(10L, "Asha"), lead(11L, "Ravi")));

        LeadPage result = service.list(null, null, null, null, null, null, null, null, null, 2, 2);

        assertThat(result.rows()).extracting(LeadView::name).containsExactly("Ravi", "Asha");
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.total()).isEqualTo(5L);
    }

    /** A 1-indexed page and a ceiling on size, exactly like the Customers book. */
    @Test
    void list_clampsPageAndSize() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        when(bookQuery.count(any(), any())).thenReturn(0L);
        when(bookQuery.pageIds(any(), any(), eq(0), eq(LeadService.MAX_PAGE_SIZE))).thenReturn(List.of());

        LeadPage result = service.list(null, null, null, null, null, null, null, null, null, 0, 500);

        assertThat(result.page()).isEqualTo(1);
        assertThat(result.size()).isEqualTo(LeadService.MAX_PAGE_SIZE);
        verify(bookQuery).pageIds(any(), any(), eq(0), eq(LeadService.MAX_PAGE_SIZE));
    }

    /** An unregistered column (the DSA scope among them) never becomes a filter, however it is spelled. */
    @Test
    void list_dropsUnregisteredColumnFilters() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        when(bookQuery.count(any(), any())).thenReturn(0L);
        when(bookQuery.pageIds(any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(List.of());

        service.list(null, null, null, null, null, null, null, null, null, 1, 25,
                java.util.Map.of("f.ownerDsaId", "in:7", "f.source", "in:DSA"));

        ArgumentCaptor<com.navix.common.query.FilterSpec> spec =
                ArgumentCaptor.forClass(com.navix.common.query.FilterSpec.class);
        verify(bookQuery).count(any(), spec.capture());
        assertThat(spec.getValue().filters().keySet()).containsExactly("source");
    }

    private static Lead lead(Long id, String name) {
        Lead l = new Lead();
        l.setId(id);
        l.setName(name);
        l.setMobile("9876543210");
        l.setCallStatus("NOT_CALLED");
        l.setCreatedByStaffId(42L);
        return l;
    }
}
