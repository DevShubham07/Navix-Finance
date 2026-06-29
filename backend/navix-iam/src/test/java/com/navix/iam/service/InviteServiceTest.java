package com.navix.iam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.AcceptInviteRequest;
import com.navix.iam.dto.StaffDtos.CreateInviteRequest;
import com.navix.iam.dto.StaffDtos.InviteResponse;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.entity.StaffInvite;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffInviteRepository;
import com.navix.iam.repository.StaffUserRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class InviteServiceTest {

    @Mock
    private StaffInviteRepository inviteRepository;
    @Mock
    private StaffUserRepository staffUserRepository;

    private InviteService inviteService;

    @BeforeEach
    void setUp() {
        inviteService = new InviteService(inviteRepository, staffUserRepository, event -> {});
        // listInvites/createInvite are ADMIN-only; default the actor to ADMIN (accept stays open).
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
    }

    @AfterEach
    void tearDown() {
        ActorContext.clear();
    }

    @Test
    void createInviteRejectsNonAdmin() {
        ActorContext.set(new CurrentActor("9", "Exec", "CREDIT_EXECUTIVE"));
        assertThatThrownBy(() -> inviteService.createInvite(
                new CreateInviteRequest("x@navix.test", StaffRole.ACCOUNTANT)))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("FORBIDDEN_ROLE");
    }

    private static StaffInvite invite(StaffRole role, Instant expiresAt, Instant acceptedAt) {
        StaffInvite i = new StaffInvite();
        i.setId(1L);
        i.setEmail("new@navix.test");
        i.setRole(role);
        i.setToken("tok-123");
        i.setExpiresAt(expiresAt);
        i.setAcceptedAt(acceptedAt);
        return i;
    }

    @Test
    void createInviteMintsTokenAndExpiry() {
        when(inviteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        InviteResponse result = inviteService.createInvite(
                new CreateInviteRequest("new@navix.test", StaffRole.CREDIT_EXECUTIVE));

        assertThat(result.email()).isEqualTo("new@navix.test");
        assertThat(result.role()).isEqualTo(StaffRole.CREDIT_EXECUTIVE);
        assertThat(result.token()).isNotBlank();
        assertThat(result.expiresAt()).isAfter(Instant.now());
        // ~7-day TTL (allow a small clock skew window)
        assertThat(result.expiresAt()).isBefore(Instant.now().plus(8, ChronoUnit.DAYS));
        assertThat(result.expiresAt()).isAfter(Instant.now().plus(6, ChronoUnit.DAYS));
    }

    @Test
    void acceptInviteActivatesNewStaff() {
        StaffInvite pending = invite(StaffRole.CREDIT_HEAD,
                Instant.now().plus(1, ChronoUnit.DAYS), null);
        when(inviteRepository.findByToken("tok-123")).thenReturn(Optional.of(pending));
        when(staffUserRepository.findByEmail("new@navix.test")).thenReturn(Optional.empty());
        when(staffUserRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inviteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaffResponse result = inviteService.acceptInvite(new AcceptInviteRequest("tok-123", "Alice"));

        assertThat(result.name()).isEqualTo("Alice");
        assertThat(result.email()).isEqualTo("new@navix.test");
        assertThat(result.role()).isEqualTo(StaffRole.CREDIT_HEAD);
        assertThat(result.status()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(pending.getAcceptedAt()).isNotNull();
    }

    @Test
    void acceptInviteReactivatesExistingStaffByEmail() {
        StaffInvite pending = invite(StaffRole.COLLECTION_HEAD,
                Instant.now().plus(1, ChronoUnit.DAYS), null);
        StaffUser existing = new StaffUser();
        existing.setId(42L);
        existing.setEmail("new@navix.test");
        existing.setName("Old Name");
        existing.setRole(StaffRole.COLLECTION_EXECUTIVE);
        existing.setStatus(StaffStatus.DISABLED);
        when(inviteRepository.findByToken("tok-123")).thenReturn(Optional.of(pending));
        when(staffUserRepository.findByEmail("new@navix.test")).thenReturn(Optional.of(existing));
        when(staffUserRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(inviteRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        StaffResponse result = inviteService.acceptInvite(new AcceptInviteRequest("tok-123", "New Name"));

        assertThat(result.id()).isEqualTo(42L);
        assertThat(result.status()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(result.role()).isEqualTo(StaffRole.COLLECTION_HEAD);
        assertThat(existing.getName()).isEqualTo("New Name");
    }

    @Test
    void acceptInviteRejectsUnknownToken() {
        when(inviteRepository.findByToken("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inviteService.acceptInvite(new AcceptInviteRequest("nope", "Alice")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVALID_INVITE");
    }

    @Test
    void acceptInviteRejectsExpiredToken() {
        StaffInvite expired = invite(StaffRole.ACCOUNTANT,
                Instant.now().minus(1, ChronoUnit.DAYS), null);
        when(inviteRepository.findByToken("tok-123")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> inviteService.acceptInvite(new AcceptInviteRequest("tok-123", "Alice")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVITE_EXPIRED");
    }

    @Test
    void acceptInviteRejectsAlreadyAccepted() {
        StaffInvite accepted = invite(StaffRole.KYC_APPROVER,
                Instant.now().plus(1, ChronoUnit.DAYS), Instant.now().minus(1, ChronoUnit.HOURS));
        when(inviteRepository.findByToken("tok-123")).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> inviteService.acceptInvite(new AcceptInviteRequest("tok-123", "Alice")))
                .isInstanceOf(BusinessException.class)
                .extracting("code").isEqualTo("INVITE_ALREADY_ACCEPTED");
    }
}
