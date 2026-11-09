package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.exception.ResourceNotFoundException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.sms.SmsGateway;
import com.navix.common.whatsapp.WhatsAppGateway;
import com.navix.loan.dto.OfferDtos.ReferenceView;
import com.navix.loan.entity.ApplicationReference;
import com.navix.loan.domain.ApplicationStatus;
import com.navix.loan.entity.CustomerProfile;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationReferenceRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The code a reference gives the borrower to confirm they agree to be named.
 *
 * <p>Deliberately not transactional: each cap is one atomic UPDATE that commits by itself, and the
 * provider calls run outside any database transaction.
 */
@Service
@RequiredArgsConstructor
public class ReferenceOtpService {

    public static final String FLAG = "reference-otp";
    static final String SMS_DLT_KEY = "REFERENCE_OTP";
    static final String WHATSAPP_NOTICE = "db_reference_added_v1_164";

    private static final Duration TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_WAIT = Duration.ofSeconds(60);
    /** The recipient is a third party who never signed up, so this also rations messages to them. */
    static final int MAX_SENDS = 6;
    static final int MAX_ATTEMPTS = 5;
    /** DLT's limit on one variable. */
    private static final int MAX_NAME = 30;

    private static final Logger log = LoggerFactory.getLogger(ReferenceOtpService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final FeatureFlagService featureFlags;
    private final ApplicationReferenceRepository references;
    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final JourneyService journey;
    private final SmsGateway sms;
    private final WhatsAppGateway whatsApp;

    private Clock clock = Clock.systemUTC();

    void setClock(Clock clock) {
        this.clock = clock;
    }

    public void send(Long appId, int slot) {
        ApplicationReference ref = requireReference(appId, slot);
        if (ref.getVerifiedAt() != null) {
            return;
        }
        if (!ref.isOtpRequired()) {
            throw new BusinessException("NOT_APPLICABLE", "This reference does not need a code");
        }
        String borrower = borrowerName(appId);
        boolean firstToThisNumber = ref.getOtpLastSentAt() == null;
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        Instant now = clock.instant();
        if (references.claimOtpSend(ref.getId(), hash(ref.getId(), code), now.plus(TTL), now,
                now.minus(RESEND_WAIT), MAX_SENDS) == 0) {
            if (ref.getOtpSendCount() >= MAX_SENDS) {
                throw new BusinessException("REFERENCE_OTP_LIMIT", "Too many codes sent to this reference");
            }
            throw new BusinessException("REFERENCE_OTP_COOLDOWN", "Please wait a minute before asking for another code");
        }

        String number = "91" + ref.getMobile();
        boolean sent = false;
        try {
            sms.send(number, "DhanBoost customer " + borrower + " named you as a loan reference. If you agree, share OTP "
                    + code + " with them. - DhanBoost", SMS_DLT_KEY);
            sent = true;
        } catch (RuntimeException e) {
            log.warn("reference OTP SMS failed app={} slot={}: {}", appId, slot, e.getClass().getSimpleName());
        }
        if (firstToThisNumber) {
            try {
                // WhatsApp's OTP text is fixed by Meta and cannot say who is asking, so the notice goes first.
                whatsApp.sendTemplate(number, WHATSAPP_NOTICE, List.of(ref.getFullName(), borrower));
            } catch (RuntimeException e) {
                log.warn("reference notice failed app={} slot={}: {}", appId, slot, e.getClass().getSimpleName());
            }
        }
        try {
            whatsApp.sendOtp(number, code);
            sent = true;
        } catch (RuntimeException e) {
            log.warn("reference OTP WhatsApp failed app={} slot={}: {}", appId, slot, e.getClass().getSimpleName());
        }
        if (!sent) {
            throw new BusinessException("REFERENCE_OTP_NOT_SENT", "We could not send the code. Please try again.");
        }
    }

    public List<ReferenceView> verify(Long appId, int slot, String otp) {
        ApplicationReference ref = requireReference(appId, slot);
        if (ref.getVerifiedAt() == null) {
            Instant now = clock.instant();
            if (references.claimOtpAttempt(ref.getId(), now, MAX_ATTEMPTS) == 0) {
                throw new BusinessException("REFERENCE_OTP_EXPIRED", "This code has expired. Please ask for a new one.");
            }
            if (ref.getOtpHash() == null || !MessageDigest.isEqual(ascii(hash(ref.getId(), otp.trim())), ascii(ref.getOtpHash()))) {
                throw new BusinessException("REFERENCE_OTP_INVALID", "That code is not correct");
            }
            references.markOtpVerified(ref.getId(), now);
        }
        List<ApplicationReference> rows = references.findByApplicationIdOrderBySlotAsc(appId);
        if (rows.stream().noneMatch(ApplicationReference::isOtpPending)) {
            journey.advance(appId, JourneyService.OfferStep.OFFER_SUMMARY);
        }
        return rows.stream().map(ReferenceView::of).toList();
    }

    private ApplicationReference requireReference(Long appId, int slot) {
        if (!featureFlags.isEnabled(FLAG, false)) {
            throw new BusinessException("NOT_APPLICABLE", "Reference confirmation is not switched on");
        }
        LoanApplication app = applications.findById(appId)
                .orElseThrow(() -> new ResourceNotFoundException("LoanApplication", String.valueOf(appId)));
        if (app.getStatus() != ApplicationStatus.SANCTIONED) {
            throw new BusinessException("NOT_APPLICABLE", "This offer isn't ready");
        }
        return references.findByApplicationIdAndSlot(appId, (short) slot)
                .orElseThrow(() -> new ResourceNotFoundException("ApplicationReference", String.valueOf(slot)));
    }

    private String borrowerName(Long appId) {
        String name = profiles.findByApplicationId(appId).map(CustomerProfile::getFullName).map(String::trim).orElse("");
        if (name.isEmpty()) {
            throw new BusinessException("REFERENCE_OTP_NOT_SENT", "We could not send the code. Please contact support.");
        }
        return name.length() > MAX_NAME ? name.substring(0, MAX_NAME).trim() : name;
    }

    private static String hash(Long id, String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ascii(id + ":" + code)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
