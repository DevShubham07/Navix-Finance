package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.notification.event.WaitlistSubmittedEvent;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.common.util.Aadhaar;
import com.navix.loan.entity.OnboardingWaitlist;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.OnboardingWaitlistRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Waitlist mode" (V76): while {@value #FLAG} is on, a borrower who has never held a loan is not
 * onboarded. They get one plain form instead, stored here untouched by any vendor, and the product
 * tells them it is under review. Everything with a loan behind it — repaid or live — is unaffected,
 * reborrow included; the frozen set is new mobiles, DRAFTs and files mid-pipeline with no loan yet.
 *
 * <p>Validation is local only (shape of the email, PAN regex, Aadhaar checksum): the whole point is
 * to spend nothing on Signzy / Digitap / Fintrix for people we are not going to lend to right now.
 * A PAN or Aadhaar already held by another customer, or by another waitlist row, is refused with a
 * message — the business chose being told over being quiet, the same trade-off as
 * {@code DUPLICATE_PAN} on intake.
 */
@Service
@RequiredArgsConstructor
public class OnboardingWaitlistService {

    public static final String FLAG = "onboarding-paused";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final Pattern MOBILE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern PAN = Pattern.compile("^[A-Z]{5}\\d{4}[A-Z]$");

    private final FeatureFlagService featureFlags;
    private final OnboardingWaitlistRepository waitlistRepository;
    private final LoanRepository loanRepository;
    private final CustomerProfileRepository profileRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** Where the signed-in borrower stands: {@code OPEN} (normal app), {@code FORM} or {@code SUBMITTED}. */
    public record GateView(boolean paused, String gate, SubmissionView submission) {
    }

    /** What the borrower sees back — Aadhaar reduced to its last four. */
    public record SubmissionView(String fullName, String email, String mobile, String pan,
                                 String aadhaarLast4, Instant createdAt) {
        static SubmissionView of(OnboardingWaitlist w) {
            return new SubmissionView(w.getFullName(), w.getEmail(), w.getMobile(), w.getPan(),
                    Aadhaar.lastFour(w.getAadhaar()), w.getCreatedAt());
        }
    }

    /** The ADMIN list row — identifiers in full; this is the only surface that shows them. */
    public record WaitlistRow(Long id, Long customerId, String fullName, String email, String mobile,
                              String pan, String aadhaar, Instant createdAt) {
        static WaitlistRow of(OnboardingWaitlist w) {
            return new WaitlistRow(w.getId(), w.getCustomerId(), w.getFullName(), w.getEmail(),
                    w.getMobile(), w.getPan(), w.getAadhaar(), w.getCreatedAt());
        }
    }

    public record SubmitRequest(String fullName, String email, String mobile, String pan, String aadhaar) {
    }

    /** The one flag read the logged-out signup screen needs — nothing else leaks out anonymously. */
    @Transactional(readOnly = true)
    public boolean paused() {
        return featureFlags.isEnabled(FLAG, false);
    }

    @Transactional(readOnly = true)
    public GateView gate() {
        Long customerId = requireBorrower();
        return gateFor(customerId);
    }

    @Transactional
    public SubmissionView submit(SubmitRequest req) {
        Long customerId = requireBorrower();
        GateView gate = gateFor(customerId);
        if ("OPEN".equals(gate.gate())) {
            throw new BusinessException("ONBOARDING_OPEN", "Onboarding is open — continue your application instead");
        }
        if (gate.submission() != null) {
            return gate.submission(); // one submission per mobile; a repeat just echoes it
        }

        String fullName = req.fullName() == null ? "" : req.fullName().trim();
        if (fullName.isEmpty()) {
            throw new BusinessException("INVALID_NAME", "Enter your full name");
        }
        String email = req.email() == null ? "" : req.email().trim();
        if (!EMAIL.matcher(email).matches()) {
            throw new BusinessException("INVALID_EMAIL", "Enter a valid email address");
        }
        String mobile = req.mobile() == null ? "" : req.mobile().replaceAll("\\D", "");
        if (mobile.length() == 12 && mobile.startsWith("91")) {
            mobile = mobile.substring(2);
        }
        if (!MOBILE.matcher(mobile).matches()) {
            throw new BusinessException("INVALID_MOBILE", "Enter a valid 10-digit mobile number");
        }
        String pan = req.pan() == null ? "" : req.pan().trim().toUpperCase(Locale.ROOT);
        if (!PAN.matcher(pan).matches()) {
            throw new BusinessException("INVALID_PAN", "Enter a valid PAN");
        }
        String aadhaar = Aadhaar.normalize(req.aadhaar());
        if (!Aadhaar.isValid(aadhaar)) {
            throw new BusinessException("INVALID_AADHAAR", "Enter a valid 12-digit Aadhaar number");
        }
        if (profileRepository.existsPanForOtherCustomer(pan, customerId)
                || waitlistRepository.existsByPanAndCustomerIdNot(pan, customerId)) {
            throw new BusinessException("DUPLICATE_PAN", "This PAN is already registered with another customer.");
        }
        if (!profileRepository.findOtherCustomerIdsByAadhaar(aadhaar, customerId).isEmpty()
                || waitlistRepository.existsByAadhaarAndCustomerIdNot(aadhaar, customerId)) {
            throw new BusinessException("DUPLICATE_AADHAAR", "This Aadhaar is already registered with another customer.");
        }

        OnboardingWaitlist row = new OnboardingWaitlist();
        row.setCustomerId(customerId);
        row.setFullName(fullName);
        row.setEmail(email);
        row.setMobile(mobile);
        row.setPan(pan);
        row.setAadhaar(aadhaar);
        if (row.getCreatedAt() == null) {
            row.setCreatedAt(Instant.now());
        }
        OnboardingWaitlist saved = waitlistRepository.save(row);
        eventPublisher.publishEvent(new WaitlistSubmittedEvent(customerId, saved.getCreatedAt()));
        return SubmissionView.of(saved);
    }

    /** The waitlist, newest first; ADMIN only — it is raw PII typed by strangers. */
    @Transactional(readOnly = true)
    public List<WaitlistRow> list(String q) {
        requireAdmin();
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        return waitlistRepository.findAllByOrderByCreatedAtDesc().stream()
                .filter(w -> needle.isEmpty() || matches(w, needle))
                .map(WaitlistRow::of)
                .toList();
    }

    /** Contact details for the notification engine when a waitlist customer has no KYC profile. */
    @Transactional(readOnly = true)
    public Optional<OnboardingWaitlist> find(Long customerId) {
        return waitlistRepository.findByCustomerId(customerId);
    }

    private GateView gateFor(Long customerId) {
        if (!paused() || !loanRepository.findByCustomerId(customerId).isEmpty()) {
            return new GateView(false, "OPEN", null);
        }
        return waitlistRepository.findByCustomerId(customerId)
                .map(w -> new GateView(true, "SUBMITTED", SubmissionView.of(w)))
                .orElseGet(() -> new GateView(true, "FORM", null));
    }

    private static boolean matches(OnboardingWaitlist w, String needle) {
        return contains(w.getFullName(), needle) || contains(w.getMobile(), needle)
                || contains(w.getEmail(), needle) || contains(w.getPan(), needle);
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private static Long requireBorrower() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"BORROWER".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role BORROWER");
        }
        return Long.valueOf(actor.id());
    }

    private static void requireAdmin() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "This action requires role ADMIN");
        }
    }
}
