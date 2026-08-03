package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
}
