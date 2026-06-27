package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.CreateStaffRequest;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.dto.StaffDtos.UpdateStaffRequest;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class StaffServiceTest {

    @Mock
    private StaffUserRepository staffUserRepository;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private StaffService staffService;

    @BeforeEach
    void setUp() {
        staffService = new StaffService(staffUserRepository, passwordEncoder);
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    private static StaffUser staff(Long id, StaffRole role, StaffStatus status) {
        StaffUser u = new StaffUser();
        u.setId(id);
        u.setEmail("jane@navix.test");
        u.setName("Jane");
        u.setRole(role);
        u.setStatus(status);
        return u;
    }

    @Test
    void listStaffMapsAllToViews() {
        when(staffUserRepository.findAll())
                .thenReturn(List.of(staff(1L, StaffRole.ADMIN, StaffStatus.ACTIVE)));

        List<StaffResponse> result = staffService.listStaff();

        assertThat(result).singleElement()
                .satisfies(r -> {
                    assertThat(r.id()).isEqualTo(1L);
                    assertThat(r.role()).isEqualTo(StaffRole.ADMIN);
                    assertThat(r.status()).isEqualTo(StaffStatus.ACTIVE);
                });
    }

    @Test
    void getStaffReturnsView() {
        when(staffUserRepository.findById(5L))
                .thenReturn(Optional.of(staff(5L, StaffRole.CREDIT_HEAD, StaffStatus.ACTIVE)));

        StaffResponse result = staffService.getStaff(5L);

        assertThat(result.id()).isEqualTo(5L);
        assertThat(result.role()).isEqualTo(StaffRole.CREDIT_HEAD);
    }

    @Test
    void getStaffNotFoundThrows() {
        when(staffUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffService.getStaff(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("StaffUser");
    }

    @Test
    void updateStaffChangesRoleAndStatus() {
        StaffUser existing = staff(5L, StaffRole.CREDIT_EXECUTIVE, StaffStatus.INVITED);
        when(staffUserRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(staffUserRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaffResponse result = staffService.updateStaff(5L,
                new UpdateStaffRequest(StaffRole.CREDIT_HEAD, StaffStatus.ACTIVE));

        assertThat(result.role()).isEqualTo(StaffRole.CREDIT_HEAD);
        assertThat(result.status()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(existing.getRole()).isEqualTo(StaffRole.CREDIT_HEAD);
        assertThat(existing.getStatus()).isEqualTo(StaffStatus.ACTIVE);
    }

    @Test
    void updateStaffNotFoundThrows() {
        when(staffUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffService.updateStaff(99L,
                new UpdateStaffRequest(StaffRole.ADMIN, StaffStatus.ACTIVE)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void disableStaffSetsStatusDisabled() {
        StaffUser existing = staff(5L, StaffRole.ACCOUNTANT, StaffStatus.ACTIVE);
        when(staffUserRepository.findById(5L)).thenReturn(Optional.of(existing));
        when(staffUserRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        staffService.disableStaff(5L);

        assertThat(existing.getStatus()).isEqualTo(StaffStatus.DISABLED);
    }

    @Test
    void disableStaffNotFoundThrows() {
        when(staffUserRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> staffService.disableStaff(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createStaffAsAdminHashesPasswordAndActivates() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(staffUserRepository.findByEmail("new@navix.test")).thenReturn(Optional.empty());
        when(staffUserRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaffResponse result = staffService.createStaff(
                new CreateStaffRequest("new@navix.test", "New Person", StaffRole.KYC_APPROVER, "secret123"));

        assertThat(result.email()).isEqualTo("new@navix.test");
        assertThat(result.role()).isEqualTo(StaffRole.KYC_APPROVER);
        assertThat(result.status()).isEqualTo(StaffStatus.ACTIVE);
        ArgumentCaptor<StaffUser> captor = ArgumentCaptor.forClass(StaffUser.class);
        verify(staffUserRepository).save(captor.capture());
        // password is BCrypt-hashed (not stored in clear) and verifies against the raw input
        assertThat(captor.getValue().getPasswordHash()).isNotNull().isNotEqualTo("secret123");
        assertThat(passwordEncoder.matches("secret123", captor.getValue().getPasswordHash())).isTrue();
    }

    @Test
    void createStaffDuplicateEmailThrows() {
        ActorContext.set(new CurrentActor("10", "Admin", "ADMIN"));
        when(staffUserRepository.findByEmail("dupe@navix.test"))
                .thenReturn(Optional.of(staff(1L, StaffRole.ADMIN, StaffStatus.ACTIVE)));

        assertThatThrownBy(() -> staffService.createStaff(
                new CreateStaffRequest("dupe@navix.test", "Dupe", StaffRole.ACCOUNTANT, "secret123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already exists");
        verify(staffUserRepository, never()).save(any());
    }

    @Test
    void createStaffNonAdminThrowsForbidden() {
        ActorContext.set(new CurrentActor("2", "Exec", "CREDIT_EXECUTIVE"));

        assertThatThrownBy(() -> staffService.createStaff(
                new CreateStaffRequest("x@navix.test", "X", StaffRole.ACCOUNTANT, "secret123")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("ADMIN");
        verify(staffUserRepository, never()).save(any());
    }
}
