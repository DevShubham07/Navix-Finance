package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailResult;
import com.navix.notification.suppression.EmailSuppressionService;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Unit tests for the email-only forgot-password lookups. The mobile filter is gone: a staff/borrower
 * match now turns solely on the email, and a miss must still send nothing (no account-enumeration).
 */
@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock
    private PasswordResetTokenRepository tokenRepository;
    @Mock
    private BorrowerCredentialRepository credentialRepository;
    @Mock
    private StaffUserRepository staffRepository;
    @Mock
    private CustomerProfileRepository profileRepository;
    @Mock
    private com.navix.loan.repository.LoanApplicationRepository applicationRepository;
    @Mock
    private com.navix.config.StaffSessionRegistry staffSessionRegistry;
    @Mock
    private EmailClient emailClient;
    @Mock
    private EmailSuppressionService suppression;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private PasswordResetService service;

    @BeforeEach
    void setUp() {
        EmailProperties emailProperties = new EmailProperties("log", true, null, null, null);
        service = new PasswordResetService(tokenRepository, credentialRepository, staffRepository,
                profileRepository, applicationRepository, staffSessionRegistry, passwordEncoder, emailClient, suppression, emailProperties,
                "http://localhost:3000");
    }

    private static StaffUser staff(long id, String email, StaffStatus status) {
        StaffUser s = new StaffUser();
        s.setId(id);
        s.setEmail(email);
        s.setStatus(status);
        return s;
    }

    private static CustomerProfile profile(String email, String mobile) {
        CustomerProfile p = new CustomerProfile();
        p.setEmail(email);
        p.setMobile(mobile);
        return p;
    }

    @Test
    void staffResetSendsOnEmailMatchAgainstActiveAccount() {
        when(staffRepository.findByEmail("jane@navix.test"))
                .thenReturn(Optional.of(staff(5L, "jane@navix.test", StaffStatus.ACTIVE)));
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref"));

        service.requestStaffReset("jane@navix.test");

        verify(tokenRepository).save(any());
        verify(emailClient).send(any());
    }

    @Test
    void staffResetSendsNothingWhenAccountIsNotActive() {
        when(staffRepository.findByEmail("jane@navix.test"))
                .thenReturn(Optional.of(staff(5L, "jane@navix.test", StaffStatus.DISABLED)));

        service.requestStaffReset("jane@navix.test");

        verify(tokenRepository, never()).save(any());
        verify(emailClient, never()).send(any());
    }

    @Test
    void staffResetSendsNothingOnNoMatch() {
        when(staffRepository.findByEmail("nobody@navix.test")).thenReturn(Optional.empty());

        service.requestStaffReset("nobody@navix.test");

        verify(tokenRepository, never()).save(any());
        verify(emailClient, never()).send(any());
    }

    @Test
    void staffResetIsANoOpOnBlankEmail() {
        service.requestStaffReset("  ");

        verify(staffRepository, never()).findByEmail(any());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void borrowerResetTokenSubjectIsTheApplicationsCustomerNotTheMobileDerivedId() {
        // The profile mobile is borrower-editable; aiming it at someone else's last 7 digits (9876543)
        // used to retarget the token at THEIR customer id. The subject must come from the account.
        CustomerProfile p = profile("borrower@navix.test", "1239876543");
        p.setApplicationId(11L);
        com.navix.loan.entity.LoanApplication app = new com.navix.loan.entity.LoanApplication();
        app.setCustomerId(77L);
        when(profileRepository.findFirstByEmailIgnoreCaseAndPersonalEmailVerifiedTrueOrderByApplicationIdDesc(
                "borrower@navix.test")).thenReturn(Optional.of(p));
        when(applicationRepository.findById(11L)).thenReturn(Optional.of(app));
        when(emailClient.send(any())).thenReturn(EmailResult.ok("ref"));

        service.requestBorrowerReset("borrower@navix.test");

        org.mockito.ArgumentCaptor<PasswordResetToken> saved =
                org.mockito.ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        assertThat(saved.getValue().getSubjectId()).isEqualTo(77L);
        assertThat(AuthController.deriveCustomerId("1239876543")).isNotEqualTo(77L);
        verify(emailClient).send(any());
    }

    @Test
    void borrowerResetSendsNothingWhenNoOtpVerifiedProfileMatches() {
        when(profileRepository.findFirstByEmailIgnoreCaseAndPersonalEmailVerifiedTrueOrderByApplicationIdDesc(
                "borrower@navix.test")).thenReturn(Optional.empty());

        service.requestBorrowerReset("borrower@navix.test");

        verify(tokenRepository, never()).save(any());
        verify(emailClient, never()).send(any());
    }

    @Test
    void borrowerResetSendsNothingOnNoMatch() {
        when(profileRepository.findFirstByEmailIgnoreCaseAndPersonalEmailVerifiedTrueOrderByApplicationIdDesc("nobody@navix.test"))
                .thenReturn(Optional.empty());

        service.requestBorrowerReset("nobody@navix.test");

        verify(tokenRepository, never()).save(any());
        verify(emailClient, never()).send(any());
    }

    @Test
    void borrowerResetIsANoOpOnBlankEmail() {
        service.requestBorrowerReset(null);

        verify(profileRepository, never()).findFirstByEmailIgnoreCaseAndPersonalEmailVerifiedTrueOrderByApplicationIdDesc(any());
        verify(tokenRepository, never()).save(any());
    }
}
