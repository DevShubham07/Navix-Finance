package com.navix.verification.service;

import static com.navix.verification.support.ProviderJson.ref;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.FintrixCrifClient;
import com.navix.verification.dto.FintrixDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps {@link FintrixCrifClient} onto the provider-neutral {@link VerificationPort}. Fintrix is the
 * bureau PRIMARY (see {@code RoutingVerificationPort}) and offers ONLY the bureau capability — every
 * other method throws {@link CapabilityNotSupportedException} so the router skips straight to the next
 * provider for PAN/email/penny-drop/DigiLocker/liveness/address/employment.
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
    private final FeatureFlagService featureFlags;

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        // defaultWhenMissing=true: a fresh environment with no feature_flag row must still get the
        // primary bureau provider — only an explicit `enabled=false` row turns Fintrix off. Throwing
        // CapabilityNotSupportedException (not VerificationException) makes the router treat a
        // deliberate switch-off exactly like "Fintrix doesn't support bureau" — a silent skip straight
        // to Digitap — rather than recording/rethrowing it as an upstream failure.
        if (!featureFlags.isEnabled(KILL_SWITCH_FLAG, true)) {
            throw new CapabilityNotSupportedException(
                    "Fintrix bureau disabled by feature flag '" + KILL_SWITCH_FLAG + "'");
        }
        FintrixDtos.CrifResponse r = crifClient.pull(name, mobile, ref(clientRef));
        BureauReportFacts f = r.facts();
        // reportUrl carries Fintrix's own credit_report_link neutrally onto VerificationPort (see its
        // javadoc) — ApplicationVerificationService ingests + scrubs it without ever seeing the Fintrix
        // envelope shape. Still HTML-escaped exactly as Fintrix sends it; unescaping is the caller's job.
        return new BureauCheck(r.txnId(), "FINTRIX_CRIF", r.score(), r.noRecord(),
                f != null ? f.activeAccounts() : null,
                f != null ? f.defaults() : null,
                f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                f, r.rawResponseJson(), r.creditReportLink());
    }

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        throw new CapabilityNotSupportedException("Fintrix has no PAN API in this package");
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
                                            String employerName, String clientRef) {
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
