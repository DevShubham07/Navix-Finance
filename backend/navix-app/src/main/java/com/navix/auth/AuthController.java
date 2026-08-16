package com.navix.auth;

import com.navix.auth.AuthDtos.AcceptInviteRequest;
import com.navix.auth.AuthDtos.AuthResponse;
import com.navix.auth.AuthDtos.BorrowerLoginRequest;
import com.navix.auth.AuthDtos.BorrowerPasswordLoginRequest;
import com.navix.auth.AuthDtos.ForgotPasswordRequest;
import com.navix.auth.AuthDtos.MessageResponse;
import com.navix.auth.AuthDtos.OtpRequestRequest;
import com.navix.auth.AuthDtos.OtpRequestResponse;
import com.navix.auth.AuthDtos.ResetPasswordRequest;
import com.navix.auth.AuthDtos.SetPasswordRequest;
import com.navix.auth.AuthDtos.StaffLoginRequest;
import com.navix.common.exception.BusinessException;
import com.navix.common.verification.OtpVerifierPort;
import com.navix.common.security.ActorContext;
import com.navix.common.security.JwtService;
import com.navix.common.util.Masking;
import com.navix.common.web.ApiResponse;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.dto.StaffDtos.StaffResponse;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.iam.service.InviteService;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.repository.CustomerProfileRepository;
import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Locale;
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
    private final CustomerProfileRepository profileRepository;
    private final BorrowerCredentialRepository credentialRepository;
    private final PasswordResetService passwordResetService;
    private final InviteService inviteService;
    private final BorrowerMobileRepository mobileRepository;
    private final AttemptLimiter limiter;
    private final com.navix.config.StaffSessionRegistry staffSessionRegistry;

    /**
     * Sign-in attempts allowed per identifier per {@link #LOGIN_WINDOW} (either audience).
     *
     * <p>Three wrong passwords, then a 30-second cool-off, then three more — rather than the previous
     * 10-per-15-min lock-out, where one bad afternoon shut a staff member out for a quarter of an
     * hour. The count is remembered for {@link #LOGIN_WINDOW} so slow guessing is caught too; only
     * the WAIT is short.
     *
     * <p>Only WRONG passwords count — {@link AttemptLimiter#clear} runs on success. Counting
     * successes at a cap of three locks out anyone who signs in on two devices in a row.
     *
     * <p>Trade-off, stated plainly: this raises the ceiling on an online password-guessing attack
     * from ~40 to ~360 attempts an hour per account. It is a throttle, not the defence — the
     * password policy and BCrypt are.
     */
    private static final int MAX_LOGIN_ATTEMPTS = 3;
    /**
     * How long wrong passwords are remembered. Must be much longer than {@link #LOGIN_COOLOFF}:
     * when both were 30s the limiter never fired against anyone typing by hand, because each
     * attempt more than 30s after the last started a fresh count.
     */
    private static final Duration LOGIN_WINDOW = Duration.ofMinutes(15);
    /** How long the borrower/staff waits once they have used up the three. */
    private static final Duration LOGIN_COOLOFF = Duration.ofSeconds(30);
    /** Lead sentence only — {@link AttemptLimiter} appends the real remaining wait. */
    private static final String TOO_MANY_LOGINS = "Too many sign-in attempts.";

    @PostMapping("/staff/login")
    public ApiResponse<AuthResponse> staffLogin(@Valid @RequestBody StaffLoginRequest req) {
        String maskedEmail = Masking.maskEmail(req.email() == null ? null : req.email().trim());
        String limitKey = "staff:" + req.email().trim().toLowerCase(Locale.ROOT);
        // Before the lookup AND before BCrypt — otherwise the endpoint is an unbounded password oracle.
        limiter.hit(limitKey, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW, LOGIN_COOLOFF, TOO_MANY_LOGINS);
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
        // Right password: the three strikes are three WRONG ones, so wipe the slate.
        limiter.clear(limitKey);

        // One live console session per staffer, ALL roles including ADMIN. A second sign-in while a
        // session is live is refused with a code the UI can distinguish (SESSION_CONFLICT), so the
        // operator gets an explicit "continue there / continue here" choice instead of silently
        // kicking their other tab. `force` is that choice, sent only after the UI has shown it.
        boolean force = Boolean.TRUE.equals(req.force());
        if (staff.getActiveSessionId() != null && !force) {
            log.warn("staff login blocked reason=SESSION_CONFLICT staffId={} email={}", staff.getId(), maskedEmail);
            throw new BusinessException("SESSION_CONFLICT",
                    "You're already signed in on another device or browser.");
        }
        String sessionId = java.util.UUID.randomUUID().toString();
        staff.setActiveSessionId(sessionId);
        staff.setActiveSessionAt(java.time.Instant.now());
        staffRepository.save(staff);
        staffSessionRegistry.invalidate(staff.getId());

        String id = String.valueOf(staff.getId());
        String token = jwtService.issue(id, staff.getName(), staff.getRole().name(),
                JwtService.AUDIENCE_STAFF, sessionId);
        log.info("staff login ok staffId={} role={} forced={}", id, staff.getRole(), force);
        return ApiResponse.ok(new AuthResponse(token, id, staff.getName(), staff.getRole().name(), null));
    }

    /**
     * Clear the caller's own live session so a clean sign-out doesn't force the NEXT login to pass
     * {@code force}. Only clears when the token's OWN {@code sid} matches the stored one — otherwise
     * a superseded session's stale logout (fired after being replaced) would wipe the session that
     * replaced it. Public route ({@code /api/auth/**}), but idempotent/no-op without a valid staff
     * bearer, so it never needs to be treated as a protected endpoint.
     */
    @PostMapping("/staff/logout")
    public ApiResponse<MessageResponse> staffLogout(jakarta.servlet.http.HttpServletRequest request) {
        String actorId = com.navix.common.security.ActorContext.get().id();
        String actorRole = com.navix.common.security.ActorContext.get().role();
        if (actorId == null || "BORROWER".equals(actorRole)) {
            return ApiResponse.ok(new MessageResponse("Signed out."));
        }
        String tokenSessionId = (String) request.getAttribute(
                com.navix.config.JwtAuthFilter.STAFF_SESSION_ID_ATTR);
        try {
            Long id = Long.valueOf(actorId);
            staffRepository.findById(id).ifPresent(s -> {
                if (tokenSessionId != null && tokenSessionId.equals(s.getActiveSessionId())) {
                    s.setActiveSessionId(null);
                    s.setActiveSessionAt(null);
                    staffRepository.save(s);
                    staffSessionRegistry.invalidate(id);
                }
            });
        } catch (NumberFormatException ignored) {
            // Non-numeric actor id — nothing to clear.
        }
        return ApiResponse.ok(new MessageResponse("Signed out."));
    }

    /**
     * Activate a staff account from a one-time invite token and set the chosen password. Public (an
     * invitee has no session yet) — the token is the credential. On success the staffer is signed in
     * immediately (a staff JWT is issued, mirroring {@code /staff/login}) so they land in the console.
     */
    @PostMapping("/staff/accept-invite")
    public ApiResponse<AuthResponse> staffAcceptInvite(@Valid @RequestBody AcceptInviteRequest req) {
        PasswordPolicy.validate(req.password());
        String passwordHash = passwordEncoder.encode(req.password());
        StaffResponse staff = inviteService.acceptInvite(req.token(), req.name().trim(), passwordHash, req.mobile());
        String id = String.valueOf(staff.id());
        // Mint a session here too — otherwise a freshly-activated staffer holds a sid-less token,
        // which the single-session filter grandfathers as "always current" (see StaffSessionRegistry),
        // exempting the very first session from the enforcement everyone else gets.
        String sessionId = java.util.UUID.randomUUID().toString();
        staffRepository.findById(staff.id()).ifPresent(s -> {
            s.setActiveSessionId(sessionId);
            s.setActiveSessionAt(java.time.Instant.now());
            staffRepository.save(s);
        });
        String token = jwtService.issue(id, staff.name(), staff.role().name(),
                JwtService.AUDIENCE_STAFF, sessionId);
        log.info("staff invite accepted staffId={} role={}", id, staff.role());
        return ApiResponse.ok(new AuthResponse(token, id, staff.name(), staff.role().name(), null));
    }

    /** Generate an OTP and SMS it to the borrower's mobile (UltronSMS). Call before login. */
    @PostMapping("/borrower/otp/request")
    public ApiResponse<OtpRequestResponse> requestBorrowerOtp(@Valid @RequestBody OtpRequestRequest req) {
        OtpVerifierPort.OtpRequestResult result = otpService.request(req.mobile(), OtpVerifierPort.LOGIN);
        return ApiResponse.ok(new OtpRequestResponse(result.sent(), result.ttlSeconds(), result.devCode()));
    }

    @PostMapping("/borrower/login")
    public ApiResponse<AuthResponse> borrowerLogin(@Valid @RequestBody BorrowerLoginRequest req) {
        if (!otpService.verify(req.mobile(), req.otp(), OtpVerifierPort.LOGIN)) {
            log.warn("borrower login failed reason=INVALID_OTP mobile={}", Masking.maskPhone(req.mobile()));
            throw new BusinessException("INVALID_OTP", "Invalid or expired OTP");
        }
        long customerId = claimCustomerId(req.mobile());
        String name = resolveBorrowerName(req.name(), req.mobile());
        String id = String.valueOf(customerId);
        String token = jwtService.issue(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER);
        log.info("borrower login ok customerId={}", customerId);
        return ApiResponse.ok(new AuthResponse(token, id, name, "BORROWER", customerId));
    }

    /** Borrower sign-in by password (the OTP-less alternative; requires a previously set password). */
    @PostMapping("/borrower/password-login")
    public ApiResponse<AuthResponse> borrowerPasswordLogin(@Valid @RequestBody BorrowerPasswordLoginRequest req) {
        String limitKey = "pw:" + req.mobile().replaceAll("\\D", "");
        limiter.hit(limitKey, MAX_LOGIN_ATTEMPTS, LOGIN_WINDOW, LOGIN_COOLOFF, TOO_MANY_LOGINS);
        long customerId = claimCustomerId(req.mobile());
        BorrowerCredential cred = credentialRepository.findById(customerId).orElse(null);
        if (cred == null) {
            log.warn("borrower password login failed reason=NO_PASSWORD_SET customerId={}", customerId);
            throw new BusinessException("NO_PASSWORD_SET",
                    "No password is set for this number. Sign in with an OTP, then add a password.");
        }
        if (!passwordEncoder.matches(req.password(), cred.getPasswordHash())) {
            log.warn("borrower password login failed reason=INVALID_CREDENTIALS customerId={}", customerId);
            throw new BusinessException("INVALID_CREDENTIALS", "Invalid mobile or password");
        }
        limiter.clear(limitKey); // as on staff login: only wrong passwords count toward the three
        String name = resolveBorrowerName(null, req.mobile());
        String id = String.valueOf(customerId);
        String token = jwtService.issue(id, name, "BORROWER", JwtService.AUDIENCE_BORROWER);
        log.info("borrower password login ok customerId={}", customerId);
        return ApiResponse.ok(new AuthResponse(token, id, name, "BORROWER", customerId));
    }

    /** Set (or replace) a password for the signed-in borrower — the optional signup step / profile. */
    @PostMapping("/borrower/set-password")
    public ApiResponse<MessageResponse> setBorrowerPassword(@Valid @RequestBody SetPasswordRequest req) {
        var actor = ActorContext.get();
        if (!"BORROWER".equals(actor.role())) {
            throw new BusinessException("UNAUTHORIZED", "Sign in as a borrower to set a password");
        }
        passwordResetService.setBorrowerPassword(Long.parseLong(actor.id()), req.password());
        return ApiResponse.ok(new MessageResponse("Password set. You can now sign in with it."));
    }

    /** Borrower forgot-password — emails a reset link only when email + mobile match (no enumeration). */
    @PostMapping("/borrower/forgot-password")
    public ApiResponse<MessageResponse> borrowerForgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestBorrowerReset(req.email(), req.mobile());
        return ApiResponse.ok(genericResetAck());
    }

    /** Borrower reset-password — redeem the one-time link token + set a new password. */
    @PostMapping("/borrower/reset-password")
    public ApiResponse<MessageResponse> borrowerResetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.password(), PasswordResetService.BORROWER);
        return ApiResponse.ok(new MessageResponse("Password updated. You can now sign in."));
    }

    /** Staff forgot-password — emails a reset link only when email + mobile match (no enumeration). */
    @PostMapping("/staff/forgot-password")
    public ApiResponse<MessageResponse> staffForgotPassword(@Valid @RequestBody ForgotPasswordRequest req) {
        passwordResetService.requestStaffReset(req.email(), req.mobile());
        return ApiResponse.ok(genericResetAck());
    }

    /** Staff reset-password — redeem the one-time link token + set a new password. */
    @PostMapping("/staff/reset-password")
    public ApiResponse<MessageResponse> staffResetPassword(@Valid @RequestBody ResetPasswordRequest req) {
        passwordResetService.resetPassword(req.token(), req.password(), PasswordResetService.STAFF);
        return ApiResponse.ok(new MessageResponse("Password updated. You can now sign in."));
    }

    /** The same acknowledgement whether or not the details matched — so neither leaks account existence. */
    private static MessageResponse genericResetAck() {
        return new MessageResponse("If those details match an account, we've emailed a reset link.");
    }

    /**
     * The borrower's display name for the session. An explicit request name (set by a brand-new
     * signup, where the borrower just typed it) wins; otherwise a returning borrower's name is read
     * from their most recent stored KYC profile (by mobile). The returning-login screen deliberately
     * sends NO name, so the identity always comes from the server here — never from a name cached on
     * the device, which previously leaked one user's name to the next on a shared browser.
     */
    private String resolveBorrowerName(String reqName, String reqMobile) {
        if (reqName != null && !reqName.isBlank()) {
            return reqName.trim();
        }
        String mobile = reqMobile == null ? "" : reqMobile.replaceAll("\\D", "");
        if (!mobile.isEmpty()) {
            String stored = profileRepository.findFirstByMobileOrderByApplicationIdDesc(mobile)
                    .map(CustomerProfile::getFullName)
                    .filter(n -> n != null && !n.isBlank())
                    .orElse(null);
            if (stored != null) {
                return stored.trim();
            }
        }
        return "Borrower";
    }

    /**
     * The caller's customer id, claiming it for this mobile the first time it is seen.
     *
     * <p>{@link #deriveCustomerId} keeps only the last 7 digits, so two different mobiles can derive
     * the SAME id. Without this check the second borrower silently lands in the first one's account —
     * their loans, their KYC, their repayments. The registry turns that into a loud failure.
     *
     * <p>ponytail: a read-then-insert race on a genuine collision surfaces as a PK violation (500)
     * rather than this 422. Still loud, which is the entire point — a lock would buy a nicer error
     * for a case that should not exist. The real fix is a surrogate customer id, not tighter locking.
     */
    private long claimCustomerId(String mobile) {
        long id = deriveCustomerId(mobile);
        String digits = mobile.replaceAll("\\D", "");
        String number = digits.length() > 10 ? digits.substring(digits.length() - 10) : digits;
        BorrowerMobile claim = mobileRepository.findById(id).orElse(null);
        if (claim == null) {
            BorrowerMobile row = new BorrowerMobile();
            row.setCustomerId(id);
            row.setMobile(number);
            mobileRepository.save(row);
        } else if (!claim.getMobile().equals(number)) {
            // Only the id is logged: colliding numbers share their last 7 digits, so both masked
            // forms are identical and printing them just looks like a bug. The id is the key ops
            // needs — `select * from borrower_mobile where customer_id = <id>` has the claimant.
            log.error("customerId collision customerId={} — two mobiles derive this id; "
                    + "the claimant is in borrower_mobile", id);
            throw new BusinessException("CUSTOMER_ID_COLLISION",
                    "This number can't be used for sign-in right now. Please contact support.");
        }
        return id;
    }

    /** Stable demo customer id from a mobile: last 7 digits (mirrors the BFF's derivation). */
    static long deriveCustomerId(String mobile) {
        String digits = mobile.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            throw new BusinessException("INVALID_MOBILE", "Mobile must contain digits");
        }
        String last7 = digits.length() > 7 ? digits.substring(digits.length() - 7) : digits;
        return Long.parseLong(last7);
    }
}
