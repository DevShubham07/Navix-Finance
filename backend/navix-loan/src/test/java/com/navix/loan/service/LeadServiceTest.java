package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.staff.StaffDirectory;
import com.navix.common.staff.StaffSummary;
import com.navix.loan.dto.LeadDtos.CreateLeadRequest;
import com.navix.loan.dto.LeadDtos.DispositionRequest;
import com.navix.loan.dto.LeadDtos.LeadView;
import com.navix.loan.entity.Lead;
import com.navix.loan.repository.LeadRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.JdbcTemplate;

@ExtendWith(MockitoExtension.class)
class LeadServiceTest {

    @Mock private LeadRepository leadRepository;
    @Mock private StaffDirectory staffDirectory;
    @Mock private JdbcTemplate jdbc;

    private LeadService service;

    @BeforeEach
    void setUp() {
        service = new LeadService(leadRepository, staffDirectory, jdbc);
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

    @Test
    void list_withoutFilters_usesAnIdDescendingSpecificationQuery() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        ArgumentCaptor<Specification<Lead>> spec = specificationCaptor();
        when(leadRepository.findAll(spec.capture(), any(Sort.class))).thenReturn(List.of());

        assertThat(service.list(null, null, null, null, null, null, null, null)).isEmpty();

        verify(leadRepository).findAll(any(Specification.class), eq(Sort.by(Sort.Direction.DESC, "id")));
        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Predicate matchAll = mock(Predicate.class);
        when(cb.conjunction()).thenReturn(matchAll);
        assertThat(spec.getValue().toPredicate(mock(Root.class), mock(CriteriaQuery.class), cb))
                .isSameAs(matchAll);
    }

    @Test
    void list_withFromAndToOnly_buildsExclusiveUtcDateRange() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        ArgumentCaptor<Specification<Lead>> spec = specificationCaptor();
        when(leadRepository.findAll(spec.capture(), any(Sort.class))).thenReturn(List.of());

        service.list(null, null, null, null,
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 8, 12), null, null);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<Lead> root = mock(Root.class);
        Path<Instant> createdAt = mock(Path.class);
        when(root.<Instant>get("createdAt")).thenReturn(createdAt);
        Predicate lower = mock(Predicate.class);
        Predicate upper = mock(Predicate.class);
        Predicate combined = mock(Predicate.class);
        when(cb.greaterThanOrEqualTo(createdAt, Instant.parse("2026-07-13T00:00:00Z"))).thenReturn(lower);
        when(cb.lessThan(createdAt, Instant.parse("2026-08-13T00:00:00Z"))).thenReturn(upper);
        when(cb.and(any(Predicate[].class))).thenReturn(combined);

        assertThat(spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb)).isSameAs(combined);
        verify(cb).greaterThanOrEqualTo(createdAt, Instant.parse("2026-07-13T00:00:00Z"));
        verify(cb).lessThan(createdAt, Instant.parse("2026-08-13T00:00:00Z"));
    }

    @Test
    void list_withQOnly_normalisesWhitespaceIntoOneCaseInsensitiveTerm() {
        ActorContext.set(new CurrentActor("42", "Tara", "TELECALLER"));
        ArgumentCaptor<Specification<Lead>> spec = specificationCaptor();
        when(leadRepository.findAll(spec.capture(), any(Sort.class))).thenReturn(List.of());

        service.list("  Ravi  ", null, null, null, null, null, null, null);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<Lead> root = mock(Root.class);
        Path<String> name = mock(Path.class);
        Path<String> mobile = mock(Path.class);
        Expression<String> lowerName = mock(Expression.class);
        when(root.<String>get("name")).thenReturn(name);
        when(root.<String>get("mobile")).thenReturn(mobile);
        when(cb.lower(name)).thenReturn(lowerName);
        Predicate byName = mock(Predicate.class);
        Predicate byMobile = mock(Predicate.class);
        Predicate either = mock(Predicate.class);
        when(cb.like(lowerName, "%ravi%")).thenReturn(byName);
        when(cb.like(mobile, "%Ravi%")).thenReturn(byMobile);
        when(cb.or(byName, byMobile)).thenReturn(either);
        when(cb.and(either)).thenReturn(mock(Predicate.class));

        spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).like(lowerName, "%ravi%");
        verify(cb).like(mobile, "%Ravi%");
    }

    @Test
    void list_withCombinedFilters_includesEverySuppliedConstraint() {
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        ArgumentCaptor<Specification<Lead>> spec = specificationCaptor();
        when(leadRepository.findAll(spec.capture(), any(Sort.class))).thenReturn(List.of());

        service.list(null, " CALLBACK ", " DSA ", 42L, null, null, 2, 4);

        CriteriaBuilder cb = mock(CriteriaBuilder.class);
        Root<Lead> root = mock(Root.class);
        Path<String> callStatus = mock(Path.class);
        Path<String> source = mock(Path.class);
        Path<Long> createdBy = mock(Path.class);
        Path<Integer> rating = mock(Path.class);
        when(root.<String>get("callStatus")).thenReturn(callStatus);
        when(root.<String>get("source")).thenReturn(source);
        when(root.<Long>get("createdByStaffId")).thenReturn(createdBy);
        when(root.<Integer>get("qualityRating")).thenReturn(rating);
        when(cb.equal(callStatus, "CALLBACK")).thenReturn(mock(Predicate.class));
        when(cb.equal(source, "DSA")).thenReturn(mock(Predicate.class));
        when(cb.equal(createdBy, 42L)).thenReturn(mock(Predicate.class));
        when(cb.greaterThanOrEqualTo(rating, 2)).thenReturn(mock(Predicate.class));
        when(cb.lessThanOrEqualTo(rating, 4)).thenReturn(mock(Predicate.class));
        when(cb.and(any(Predicate[].class))).thenReturn(mock(Predicate.class));

        spec.getValue().toPredicate(root, mock(CriteriaQuery.class), cb);

        verify(cb).equal(callStatus, "CALLBACK");
        verify(cb).equal(source, "DSA");
        verify(cb).equal(createdBy, 42L);
        verify(cb).greaterThanOrEqualTo(rating, 2);
        verify(cb).lessThanOrEqualTo(rating, 4);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static ArgumentCaptor<Specification<Lead>> specificationCaptor() {
        return (ArgumentCaptor) ArgumentCaptor.forClass(Specification.class);
    }
}
