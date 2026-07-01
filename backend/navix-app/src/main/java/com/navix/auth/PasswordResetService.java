package com.navix.auth;

import com.navix.common.exception.BusinessException;
import com.navix.common.util.Masking;
import com.navix.iam.domain.StaffStatus;
import com.navix.iam.entity.StaffUser;
import com.navix.iam.repository.StaffUserRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.notification.config.EmailProperties;
import com.navix.notification.email.EmailClient;
import com.navix.notification.email.EmailMessage;
import com.navix.notification.email.EmailResult;
import com.navix.notification.suppression.EmailSuppressionService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The forgot/reset-password engine for both audiences. A request (gated by a matching email + mobile,
 * or by an authenticated session) mints a one-time, 30-minute, single-use token, stores only its
 * SHA-256 hash, and emails a reset link carrying the raw token. The landing page then posts the token
 * + a new password back to {@code resetPassword}. Also the single writer of a borrower's password
 * (the optional set-password on signup / profile).
 *
 * <p>Email is sent directly via {@link EmailClient}, but suppression- and enabled-gated like
 * {@code EmailSender} — and deliberately <b>not</b> via the notification dispatcher, so the reset link
 * never lands in an in-app inbox.
 */
@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    static final String BORROWER = "BORROWER";
    static final String STAFF = "STAFF";

    private final PasswordResetTokenRepository tokenRepository;
    private final BorrowerCredentialRepository credentialRepository;
    private final StaffUserRepository staffRepository;
    private final CustomerProfileRepository profileRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailClient emailClient;
    private final EmailSuppressionService suppression;
    private final EmailProperties emailProperties;
    private final String frontendBaseUrl;
    private final SecureRandom random = new SecureRandom();

    public PasswordResetService(PasswordResetTokenRepository tokenRepository,
                                BorrowerCredentialRepository credentialRepository,
                                StaffUserRepository staffRepository,
                                CustomerProfileRepository profileRepository,
                                PasswordEncoder passwordEncoder,
                                EmailClient emailClient,
                                EmailSuppressionService suppression,
                                EmailProperties emailProperties,
                                @Value("${navix.app.frontend-base-url:http://localhost:3000}") String frontendBaseUrl) {
        this.tokenRepository = tokenRepository;
        this.credentialRepository = credentialRepository;
        this.staffRepository = staffRepository;
        this.profileRepository = profileRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailClient = emailClient;
        this.suppression = suppression;
        this.emailProperties = emailProperties;
        this.frontendBaseUrl = frontendBaseUrl.endsWith("/")
                ? frontendBaseUrl.substring(0, frontendBaseUrl.length() - 1) : frontendBaseUrl;
    }

    /** Set (or replace) the borrower's password — the optional signup step and the reset path. */
    @Transactional
    public void setBorrowerPassword(long customerId, String newPassword) {
        PasswordPolicy.validate(newPassword);
        BorrowerCredential cred = credentialRepository.findById(customerId).orElseGet(() -> {
            BorrowerCredential c = new BorrowerCredential();
            c.setCustomerId(customerId);
            return c;
        });
        cred.setPasswordHash(passwordEncoder.encode(newPassword));
        credentialRepository.save(cred);
    }

    /**
     * Borrower forgot-password: the email + mobile must match a stored KYC profile. Always returns
     * (no account-enumeration); only sends the link on a real match.
     */
    @Transactional
    public void requestBorrowerReset(String email, String mobile) {
        String normEmail = email == null ? "" : email.trim();
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (normEmail.isEmpty() || digits.isEmpty()) {
            return;
        }
        profileRepository.findFirstByMobileOrderByApplicationIdDesc(digits)
                .filter(p -> p.getEmail() != null && p.getEmail().equalsIgnoreCase(normEmail))
                .ifPresentOrElse(
                        p -> issueAndSend(BORROWER, AuthController.deriveCustomerId(digits), normEmail),
                        () -> log.info("borrower forgot-password no match mobile={}", Masking.maskPhone(digits)));
    }

    /** Staff forgot-password: the email must resolve to an ACTIVE staff whose stored mobile matches. */
    @Transactional
    public void requestStaffReset(String email, String mobile) {
        String normEmail = email == null ? "" : email.trim();
        String digits = mobile == null ? "" : mobile.replaceAll("\\D", "");
        if (normEmail.isEmpty() || digits.isEmpty()) {
            return;
        }
        staffRepository.findByEmail(normEmail)
                .filter(s -> s.getStatus() == StaffStatus.ACTIVE)
                .filter(s -> s.getMobile() != null && s.getMobile().replaceAll("\\D", "").equals(digits))
                .ifPresentOrElse(
                        s -> issueAndSend(STAFF, s.getId(), normEmail),
                        () -> log.info("staff forgot-password no match email={}", Masking.maskEmail(normEmail)));
    }

    /**
     * Redeem a reset token and set the new password. {@code expectedSubjectType} guards against a
     * borrower token being used on the staff page (or vice-versa).
     */
    @Transactional
    public void resetPassword(String rawToken, String newPassword, String expectedSubjectType) {
        PasswordPolicy.validate(newPassword);
        PasswordResetToken token = tokenRepository.findByTokenHash(sha256Hex(rawToken == null ? "" : rawToken))
                .orElseThrow(() -> new BusinessException("INVALID_TOKEN", "This reset link is invalid."));
        if (!token.getSubjectType().equals(expectedSubjectType)) {
            throw new BusinessException("INVALID_TOKEN", "This reset link is invalid.");
        }
        if (token.getUsedAt() != null) {
            throw new BusinessException("INVALID_TOKEN", "This reset link has already been used.");
        }
        if (token.getExpiresAt().isBefore(Instant.now())) {
            throw new BusinessException("EXPIRED_TOKEN", "This reset link has expired. Please request a new one.");
        }
        if (BORROWER.equals(token.getSubjectType())) {
            setBorrowerPassword(token.getSubjectId(), newPassword);
        } else {
            setStaffPassword(token.getSubjectId(), newPassword);
        }
        token.setUsedAt(Instant.now());
        tokenRepository.save(token);
    }

    private void setStaffPassword(long staffId, String newPassword) {
        StaffUser staff = staffRepository.findById(staffId)
                .orElseThrow(() -> new BusinessException("INVALID_TOKEN", "This reset link is invalid."));
        staff.setPasswordHash(passwordEncoder.encode(newPassword));
        staffRepository.save(staff);
    }

    private void issueAndSend(String subjectType, long subjectId, String email) {
        String rawToken = randomToken();
        PasswordResetToken token = new PasswordResetToken();
        token.setTokenHash(sha256Hex(rawToken));
        token.setSubjectType(subjectType);
        token.setSubjectId(subjectId);
        token.setEmail(email);
        token.setExpiresAt(Instant.now().plus(TOKEN_TTL));
        tokenRepository.save(token);

        String path = BORROWER.equals(subjectType) ? "/reset-password?token=" : "/staff/reset-password?token=";
        sendResetEmail(email, frontendBaseUrl + path + rawToken);
    }

    private void sendResetEmail(String to, String link) {
        if (!Boolean.TRUE.equals(emailProperties.enabled())) {
            log.info("EMAIL disabled — password-reset link for {} not sent", Masking.maskEmail(to));
            return;
        }
        if (suppression.isSuppressed(to)) {
            log.info("EMAIL suppressed — password-reset link for {} not sent", Masking.maskEmail(to));
            return;
        }
        // Dev/demo convenience (mirrors the SMS dev-echo): with the log-only email provider no real
        // mail goes out, so surface the reset link in the backend log for testing. Real providers
        // (smtp/ses/resend) never log the link.
        if ("log".equalsIgnoreCase(emailProperties.provider())) {
            log.info("DEV password-reset link for {}: {}", Masking.maskEmail(to), link);
        }
        String body = "Hi,\n\nWe received a request to reset your NAVIX password. Use the link below within "
                + "30 minutes to set a new one:\n\n" + link + "\n\nIf you didn't request this, you can safely "
                + "ignore this email — your password won't change.\n\n— NAVIX Finance";
        EmailResult result = emailClient.send(new EmailMessage(to, "Reset your NAVIX password", body, null));
        if (!result.ok()) {
            log.warn("password-reset email to {} failed: {}", Masking.maskEmail(to), result.error());
        }
    }

    private String randomToken() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
