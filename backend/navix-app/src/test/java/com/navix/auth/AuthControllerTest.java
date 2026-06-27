package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.JwtService;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    /** The exact hash seeded in V17 for password "Admin@12345". */
    private static final String SEEDED_HASH = "$2a$10$exssI9R9G/cJdEzsk0Apmemf5x7pUWRYlrwVWsb3WOKoq4R31pq/W";

    @Mock private StaffUserRepository staffRepository;

    private AuthController controller;
    private JwtService jwt;

    @BeforeEach
    void setUp() {
        jwt = new JwtService("test-secret-test-secret-test-secret", 3600);
        PasswordEncoder encoder = new BCryptPasswordEncoder();
        controller = new AuthController(staffRepository, jwt, encoder);
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

        var resp = controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "Admin@12345"));

        assertThat(resp.isSuccess()).isTrue();
        assertThat(resp.getData().role()).isEqualTo("ADMIN");
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("ADMIN");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_STAFF);
    }

    @Test
    void staffLogin_rejectsWrongPassword() {
        when(staffRepository.findByEmail("meera.krishnan@navix.example")).thenReturn(Optional.of(admin()));
        assertThatThrownBy(() ->
                controller.staffLogin(new StaffLoginRequest("meera.krishnan@navix.example", "wrong")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Invalid");
    }

    @Test
    void borrowerLogin_rejectsWrongOtp() {
        assertThatThrownBy(() ->
                controller.borrowerLogin(new BorrowerLoginRequest("9819000001", "000000", null, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void borrowerLogin_issuesBorrowerToken_derivingApplicantId() {
        var resp = controller.borrowerLogin(new BorrowerLoginRequest("98190 00001", "123456", "Asha", null));

        assertThat(resp.getData().applicantId()).isEqualTo(9000001L); // last 7 digits
        JwtService.Principal p = jwt.verify(resp.getData().token());
        assertThat(p.role()).isEqualTo("BORROWER");
        assertThat(p.audience()).isEqualTo(JwtService.AUDIENCE_BORROWER);
        assertThat(p.id()).isEqualTo("9000001");
    }
}
