package com.navix.auth;

import com.navix.auth.AuthDtos.AuthResponse;
import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.JwtService;
import com.navix.common.web.ApiResponse;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Real auth (P6): issues HS256 JWTs for the staff and borrower namespaces. Login is
 * moved out of the Next.js BFF into the backend; the BFF now calls these, stores the
 * returned token in its httpOnly cookie, and forwards it as a bearer.
 *
 * <p>Staff login is BCrypt vs {@code staff_user.password_hash}. Borrower OTP delivery
 * stays mocked (fixed code {@value #DEMO_OTP}) per decision 3 — only token issuance is real.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** Mocked OTP (delivery is not wired; only token issuance is real). */
    static final String DEMO_OTP = "123456";

    private final StaffUserRepository staffRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;

    @PostMapping("/staff/login")
    public ApiResponse<AuthResponse> staffLogin(@Valid @RequestBody StaffLoginRequest req) {
        StaffUser staff = staffRepository.findByEmail(req.email().trim())
                .orElseThrow(() -> new BusinessException("INVALID_CREDENTIALS", "Invalid email or password"));
        if (staff.getStatus() != StaffStatus.ACTIVE) {
            throw new BusinessException("INACTIVE_STAFF", "This staff account is not active");
        }
        if (staff.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), staff.getPasswordHash())) {
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        String id = String.valueOf(staff.getId());
        String token = jwtService.issue(id, staff.getName(), staff.getRole().name(), JwtService.AUDIENCE_STAFF);
        return ApiResponse.ok(new AuthResponse(token, id, staff.getName(), staff.getRole().name(), null));
    }

    @PostMapping("/borrower/login")
    public ApiResponse<AuthResponse> borrowerLogin(@Valid @RequestBody BorrowerLoginRequest req) {
        if (!DEMO_OTP.equals(req.otp())) {
            throw new BusinessException("INVALID_OTP", "Invalid OTP");
        }
        long applicantId = req.applicantId() != null ? req.applicantId() : deriveApplicantId(req.mobile());
        String name = req.name() != null && !req.name().isBlank() ? req.name() : "Borrower";
        String id = String.valueOf(applicantId);
        String token = jwtService.issue(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER);
        return ApiResponse.ok(new AuthResponse(token, id, name, "BORROWER", applicantId));
    }

    /** Stable demo applicant id from a mobile: last 7 digits (mirrors the BFF's derivation). */
    static long deriveApplicantId(String mobile) {
        String digits = mobile.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new BusinessException("INVALID_MOBILE", "Mobile must contain digits");
        }
        String last7 = digits.length() > 7 ? digits.substring(digits.length() - 7) : digits;
        return Long.parseLong(last7);
    }
}
