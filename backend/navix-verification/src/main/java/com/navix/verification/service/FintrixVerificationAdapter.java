package com.navix.verification.service;

import static com.navix.verification.support.ProviderJson.ref;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.FintrixCrifClient;
import com.navix.verification.client.FintrixPanClient;
import com.navix.verification.dto.FintrixDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps the Fintrix clients onto the provider-neutral {@link VerificationPort}. Fintrix serves two
 * capabilities, and is LAST in the chain for both: bureau FALLBACK behind Digitap
 * ({@link FintrixCrifClient}) and PAN fallback behind Signzy and Digitap ({@link FintrixPanClient}) —
 * see the chain order in {@code RoutingVerificationPort}. Every other
 * method throws {@link CapabilityNotSupportedException} so the router skips straight to the next
 * provider for email/penny-drop/DigiLocker/liveness/address/employment.
 */
@Component
@RequiredArgsConstructor
public class FintrixVerificationAdapter implements VerificationPort {

    /**
     * Dev-only, DB-backed kill switch (see {@code feature_flag}, migration V31) — every Fintrix call is
     * live and billable with no sandbox, so this needs to disable it instantly with no redeploy.
     */
    private static final String KILL_SWITCH_FLAG = "fintrix-bureau";

    private final FintrixCrifClient crifClient;
    private final FintrixPanClient panClient;
    private final FeatureFlagService featureFlags;

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        // defaultWhenMissing=true: a fresh environment with no feature_flag row must still get the
        // CRIF fallback leg — only an explicit `enabled=false` row turns Fintrix off. Throwing
        // CapabilityNotSupportedException (not VerificationException) makes the router treat a
        // deliberate switch-off exactly like "Fintrix doesn't support bureau" — a silent skip that
        // leaves Digitap's answer standing — rather than recording it as an upstream failure.
        if (!featureFlags.isEnabled(KILL_SWITCH_FLAG, true)) {
            throw new CapabilityNotSupportedException(
                    "Fintrix bureau disabled by feature flag '" + KILL_SWITCH_FLAG + "'");
        }
        return toBureauCheck(crifClient.pull(name, mobile, ref(clientRef)));
    }

    /**
     * Answering is billable exactly like a pull, so it sits behind the SAME kill switch. Unlike
     * {@code pullBureau} this is never reached via the provider chain — {@code RoutingVerificationPort}
     * delegates it straight here, because an {@code orderId} is meaningless to another bureau.
     */
    @Override
    public BureauCheck answerBureauChallenge(String orderId, String reportId, String answer,
                                             String name, String mobile, String clientRef) {
        if (!featureFlags.isEnabled(KILL_SWITCH_FLAG, true)) {
            throw new CapabilityNotSupportedException(
                    "Fintrix bureau disabled by feature flag '" + KILL_SWITCH_FLAG + "'");
        }
        return toBureauCheck(
                crifClient.answerChallenge(orderId, reportId, answer, ref(clientRef), name, mobile));
    }

    /**
     * One mapping for both entry points. {@code reportUrl} carries Fintrix's own credit_report_link
     * neutrally onto VerificationPort (see its javadoc) — ApplicationVerificationService ingests +
     * scrubs it without ever seeing the Fintrix envelope shape. Still HTML-escaped exactly as Fintrix
     * sends it; unescaping is the caller's job. It is always null on the answer path, whose envelope
     * carries no link.
     */
    private static BureauCheck toBureauCheck(FintrixDtos.CrifResponse r) {
        BureauReportFacts f = r.facts();
        PendingChallenge pendingChallenge = r.challenge() == null ? null
                : new PendingChallenge(r.challenge().question(), r.challenge().options(),
                        r.challenge().orderId(), r.challenge().reportId());
        return new BureauCheck(r.txnId(), "FINTRIX_CRIF", r.score(), r.noRecord(),
                f != null ? f.activeAccounts() : null,
                f != null ? f.defaults() : null,
                f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                f, r.rawResponseJson(), r.creditReportLink(), pendingChallenge);
    }

    /**
     * PAN FALLBACK behind Signzy and Digitap — see the chain order in {@code RoutingVerificationPort}. Deliberately
     * NOT behind {@link #KILL_SWITCH_FLAG}: that flag names the bureau, and PAN is reverted instead by
     * dropping fintrix from {@code NAVIX_VERIFICATION_CHAIN}, which needs no redeploy either.
     *
     * <p>{@code compliant}/{@code isSpecified} stay null: they are Signzy 206AB concepts and Fintrix
     * returns no equivalent. Both are display-only downstream, so nothing gates on them. (Fintrix does
     * send a {@code tax} boolean, deliberately not mapped here — it is not confirmed to mean 206AB
     * compliance, and mis-stating a tax status is worse than omitting it.)
     */
    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        FintrixDtos.PanResponse r = panClient.verify(pan, ref(clientRef));
        return new PanCheck(r.txnId(), "FINTRIX", "valid".equalsIgnoreCase(r.status()),
                r.fullName(), r.dob(), r.gender(),
                Boolean.TRUE.equals(r.aadhaarLinked()), r.maskedAadhaar(), r.panNumber(),
                r.addressState(), r.addressZip(),
                r.status(), r.allotmentDate(), null, null);
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no email verification API in this package");
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no address verification API");
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no bank penny-drop API");
    }

    @Override
    public EmploymentCheck verifyEmployment(String pan, String mobile, String dob, String employeeName,
                                            String employerName, String uan, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no employment/UAN API");
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no face-match API");
    }

    @Override
    public LivenessSession livenessInit(String matchImageUrl, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no interactive liveness journey");
    }

    @Override
    public LivenessResultCheck livenessResult(String token) {
        throw new CapabilityNotSupportedException("Fintrix has no interactive liveness journey");
    }

    @Override
    public DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        throw new CapabilityNotSupportedException("Fintrix has no DigiLocker consent flow");
    }

    @Override
    public DigiLockerStatus digilockerStatus(String clientId) {
        throw new CapabilityNotSupportedException("Fintrix has no DigiLocker consent flow");
    }

    @Override
    public List<DigiLockerDoc> digilockerList(String clientId) {
        throw new CapabilityNotSupportedException("Fintrix has no DigiLocker consent flow");
    }

    @Override
    public DigiLockerDownload digilockerDownload(String clientId, String fileId) {
        throw new CapabilityNotSupportedException("Fintrix has no DigiLocker consent flow");
    }

    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        throw new CapabilityNotSupportedException("Fintrix has no DigiLocker consent flow");
    }
}
