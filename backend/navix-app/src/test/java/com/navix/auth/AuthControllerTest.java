package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.security.JwtService;
import com.navix.config.StaffSessionRegistry;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    /** The exact hash seeded in V17 for password "Admin@12345". */
    private static final String SEEDED_HASH = "$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W";

    @Mock private StaffUserRepository staffRepository;
    @Mock private BorrowerOtpService otpService;
    @Mock private CustomerProfileRepository profileRepository;
    @Mock private BorrowerCredentialRepository credentialRepository;
    @Mock private PasswordResetService passwordResetService;
    @Mock private com.navix.iam.service.InviteService inviteService;
    @Mock private BorrowerMobileRepository mobileRepository;
    @Mock private StaffSessionRegistry staffSessionRegistry;

    private AuthController controller;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService("test-secret-test-secret-test-secret", 3600, 3600);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        controller = new AuthController(staffRepository, jwt, encoder, otpService, profileRepository,
                credentialRepository, passwordResetService, inviteService, mobileRepository,
                new AttemptLimiter(), staffSessionRegistry);
    }

    @AfterEach
    void clearActor() {
        ActorContext.clear();
    }

    /** A mobile that has already claimed {@code customerId}. */
    private BorrowerMobile claim(long customerId, String mobile) {
        BorrowerMobile m = new BorrowerMobile();
        m.setCustomerId(customerId);
        m.setMobile(mobile);
        return m;
    }

    private StaffUser admin() {
        StaffUser s = new StaffUser();
        s.setEmail("meera.krishnan@navix.example");
        s.setName("Meera Krishnan");
        s.setRole(StaffRole.ADMIN);
        s.setStatus(StaffStatus.ACTIVE);
        s.setPasswordHash(SEEDED_HASH);
        return s;
    }

    @Test
    void staffLogin_succeeds_withSeededDefaultPassword() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));

        var resp = controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "Admin@12345", null));

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().role()).isEqualTo("ADMIN");
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("ADMIN");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_STAFF);
        assertThat(p.sessionId()).isNotBlank(); // a fresh session id is minted on first login
    }

    @Test
    void staffLogin_rejectsWrongPassword() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));
        assertThatThrownBy(() ->
                controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "wrong", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void staffLogin_rejectsSecondSessionWithSessionConflict() {
        StaffUser admin = admin();
        admin.setActiveSessionId("existing-session");
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        assertThatThrownBy(() -> controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Admin@12345", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already signed in");
        verify(staffRepository, never()).save(any());
    }

    @Test
    void staffLogin_forceOverwritesTheSession() {
        StaffUser admin = admin();
        admin.setActiveSessionId("existing-session");
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin));

        var resp = controller.staffLogin(
                new StaffLoginRequest("meera.krishnan@navix.example", "Admin@12345", true));

        assertThat(resp.isSuccess()).isTrue();
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.sessionId()).isNotEqualTo("existing-session");
        assertThat(admin.getActiveSessionId()).isEqualTo(p.sessionId());
        verify(staffRepository).save(admin);
    }

    @Test
    void staffLogout_clearsOnlyTheCallersOwnSession() {
        StaffUser admin = admin();
        admin.setId(9L);
        admin.setActiveSessionId("my-session");
        when(staffRepository.findById(9L)).thenReturn(Optional.of(admin));
        ActorContext.set(new CurrentActor("9", "Meera Krishnan", "ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR, "my-session");

        controller.staffLogout(request);

        assertThat(admin.getActiveSessionId()).isNull();
        verify(staffSessionRegistry).invalidate(9L);
    }

    @Test
    void staffLogout_doesNotClearASessionThatAlreadySupersededTheCallersToken() {
        StaffUser admin = admin();
        admin.setId(9L);
        admin.setActiveSessionId("newer-session"); // superseded this token by a later forced login
        when(staffRepository.findById(9L)).thenReturn(Optional.of(admin));
        ActorContext.set(new CurrentActor("9", "Meera Krishnan", "ADMIN"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR, "my-old-session");

        controller.staffLogout(request);

        assertThat(admin.getActiveSessionId()).isEqualTo("newer-session");
    }

    @Test
    void borrowerLogin_rejectsWrongOtp() {
        when(otpService.verify("9819000001", "000000", OtpVerifierPort.LOGIN)).thenReturn(false);
        assertThatThrownBy(() ->
                controller.borrowerLogin(new BorrowerLoginRequest("9819000001", "000000", null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void borrowerLogin_issuesBorrowerToken_derivingCustomerId() {
        when(otpService.verify("98190 00001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findById(9000001L)).thenReturn(Optional.empty());
        var resp = controller.borrowerLogin(new BorrowerLoginRequest("98190 00001", "123456", "Asha"));

        assertThat(resp.getData().customerId()).isEqualTo(9000001L); // last 7 digits
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("BORROWER");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_BORROWER);
        assertThat(p.id()).isEqualTo("9000001");
    }

    /**
     * The collision guard: 9819000001 and 9919000001 both derive customerId 9000001. Whichever
     * mobile claimed it first keeps it; the other must fail loudly rather than land in that account.
     */
    @Test
    void borrowerLogin_rejectsMobileCollidingWithAClaimedCustomerId() {
        when(otpService.verify("9919000001", "123456", OtpVerifierPort.LOGIN)).thenReturn(true);
        when(mobileRepository.findById(9000001L))
                .thenReturn(Optional.of(claim(9000001L, "9819000001")));

        assertThatThrownBy(() ->
                controller.borrowerLogin(new BorrowerLoginRequest("9919000001", "123456", null)))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("contact support");
    }
}
