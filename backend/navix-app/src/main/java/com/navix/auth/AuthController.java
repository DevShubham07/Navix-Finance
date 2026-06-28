package com.navix.auth;

import com.navix.auth.AuthDtos.AuthResponse;
import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.OtpRequestRequest;
import com.navix.auth.AuthDtos.OtpRequestResponse;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.JwtService;
import com.navix.common.util.Masking;
import com.navix.common.web.ApiResponse;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.entity.ApplicantProfile;
import com.navix.loan.repository.ApplicantProfileRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Staff login is BCrypt vs {@code staff_user.password_hash}. Borrower login verifies a
 * real SMS-delivered OTP ({@link OtpService} → UltronSMS gateway) — request the code via
 * {@code /borrower/otp/request} first, then {@code /borrower/login}.
 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final StaffUserRepository staffRepository;
    private final JwtService jwtService;
    private final PasswordEncoder passwordEncoder;
    private final BorrowerOtpService otpService;
    private final ApplicantProfileRepository profileRepository;

    @PostMapping("/staff/login")
    public ApiResponse<AuthResponse> staffLogin(@Valid @RequestBody StaffLoginRequest req) {
        String maskedEmail = Masking.maskEmail(req.email() == null ? null : req.email().trim());
        StaffUser staff = staffRepository.findByEmail(req.email().trim()).orElse(null);
        if (staff == null) {
            log.warn("staff login failed reason=INVALID_CREDENTIALS email={}", maskedEmail);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        if (staff.getStatus() != StaffStatus.ACTIVE) {
            log.warn("staff login failed reason=INACTIVE_STAFF staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("INACTIVE_STAFF", "This staff account is not active");
        }
        if (staff.getPasswordHash() == null
                || !passwordEncoder.matches(req.password(), staff.getPasswordHash())) {
            log.warn("staff login failed reason=INVALID_CREDENTIALS staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid email or password");
        }
        String id = String.valueOf(staff.getId());
        String token = jwtService.issue(id, staff.getName(), staff.getRole().name(), JwtService.AUDIENCE_STAFF);
        log.info("staff login ok staffId={} role={}", id, staff.getRole());
        return ApiResponse.ok(new AuthResponse(token, id, staff.getName(), staff.getRole().name(), null));
    }

    /** Generate an OTP and SMS it to the borrower's mobile (UltronSMS). Call before login. */
    @PostMapping("/borrower/otp/request")
    public ApiResponse<OtpRequestResponse> requestBorrowerOtp(@Valid @RequestBody OtpRequestRequest req) {
        BorrowerOtpService.OtpRequest result = otpService.request(req.mobile());
        return ApiResponse.ok(new OtpRequestResponse(result.sent(), result.ttlSeconds(), result.devCode()));
    }

    @PostMapping("/borrower/login")
    public ApiResponse<AuthResponse> borrowerLogin(@Valid @RequestBody BorrowerLoginRequest req) {
        if (!otpService.verify(req.mobile(), req.otp())) {
            log.warn("borrower login failed reason=INVALID_OTP mobile={}", Masking.maskPhone(req.mobile()));
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        long applicantId = req.applicantId() != null ? req.applicantId() : deriveApplicantId(req.mobile());
        String name = resolveBorrowerName(req);
        String id = String.valueOf(applicantId);
        String token = jwtService.issue(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER);
        log.info("borrower login ok applicantId={}", applicantId);
        return ApiResponse.ok(new AuthResponse(token, id, name, "BORROWER", applicantId));
    }

    /**
     * The borrower's display name for the session. An explicit request name (set by a brand-new
     * signup, where the borrower just typed it) wins; otherwise a returning borrower's name is read
     * from their most recent stored KYC profile (by mobile). The returning-login screen deliberately
     * sends NO name, so the identity always comes from the server here — never from a name cached on
     * the device, which previously leaked one user's name to the next on a shared browser.
     */
    private String resolveBorrowerName(BorrowerLoginRequest req) {
        if (req.name() != null && !req.name().isBlank()) {
            return req.name().trim();
        }
        String mobile = req.mobile() == null ? "" : req.mobile().replaceAll("\\D", "");
        if (!mobile.isEmpty()) {
            String stored = profileRepository.findFirstByMobileOrderByApplicationIdDesc(mobile)
                    .map(ApplicantProfile::getFullName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
            if (stored != null) {
                return stored.trim();
            }
        }
        return "Borrower";
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
