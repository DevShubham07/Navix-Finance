package com.navix.verification.service;

import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.DigitapAddressClient;
import com.navix.verification.client.DigitapCreditClient;
import com.navix.verification.client.DigitapCrifClient;
import com.navix.verification.client.DigitapEmailClient;
import com.navix.verification.client.DigitapFaceMatchClient;
import com.navix.verification.client.DigitapPanClient;
import com.navix.verification.client.DigitapUanClient;
import com.navix.verification.dto.DigitapDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Maps the Digitap clients onto the provider-neutral {@link VerificationPort}. Digitap is the bureau PRIMARY and the
 * identity FALLBACK (see {@code RoutingVerificationPort}), and the sole provider for email + address
 * (Signzy lacks both). Capabilities Digitap does not offer throw {@link CapabilityNotSupportedException}:
 * <ul>
 *   <li>{@link #pennyDrop} — bank verification is not in the Digitap package handed to DhanBoost.</li>
 *   <li>all {@code digilocker*} — Digitap has no DigiLocker consent/OAuth e-Aadhaar flow.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DigitapVerificationAdapter implements VerificationPort {

    /** Below this face-match confidence the selfie is treated as not-live. */
    private static final double FACE_CONFIDENCE_THRESHOLD = 0.60;

    /** UAN Advanced {@code result_code}: record resolved. */
    private static final int UAN_RESULT_OK = 101;
    /** UAN Advanced {@code result_code}: identity maps to more than five UANs — nothing resolved. */
    private static final int UAN_RESULT_TOO_MANY = 104;

    /**
     * DB-backed switch for the Digitap CRIF leg (see {@code feature_flag}). Read with
     * {@code defaultWhenMissing=FALSE} — deliberately the opposite of {@code fintrix-bureau} — because
     * the endpoint has not authenticated yet (401 from Digitap, entitlement unconfirmed). Default-on
     * would put a guaranteed failing call in front of the Experian fallback that currently rescues most
     * Fintrix failures. Insert an {@code enabled=true} row to switch it on with no redeploy.
     */
    private static final String CRIF_FLAG = "digitap-crif";

    private static final Logger log = LoggerFactory.getLogger(DigitapVerificationAdapter.class);

    private final DigitapPanClient panClient;
    private final DigitapEmailClient emailClient;
    private final DigitapAddressClient addressClient;
    private final DigitapCrifClient crifClient;
    private final DigitapCreditClient creditClient;
    private final DigitapFaceMatchClient faceMatchClient;
    private final DigitapUanClient uanClient;
    private final FeatureFlagService featureFlags;

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        DigitapDtos.PanResponse r = panClient.verify(pan, clientRef);
        return new PanCheck(r.txnId(), "DIGITAP", Boolean.TRUE.equals(r.valid()), trim(r.fullName()),
                r.dob(), r.gender(), Boolean.TRUE.equals(r.aadhaarLinked()), null, pan,
                r.addressState(), r.addressZip(),
                r.panStatus(), null, null, null);
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        DigitapDtos.EmailResponse r = emailClient.verify(email, individualName, establishmentName, clientRef);
        return new EmailCheck(r.txnId(), "DIGITAP", Boolean.TRUE.equals(r.isVerified()),
                Boolean.TRUE.equals(r.isEstablishmentMatched()), Boolean.TRUE.equals(r.isIndividualMatched()),
                Boolean.TRUE.equals(r.isGenericEmail()), r.matchedEstablishment(),
                null, null, null, null, null, null, null, null, r.individualScore());
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        DigitapDtos.AddressResponse r = addressClient.verify(latitude, longitude, clientRef);
        return new AddressCheck(r.code(), "DIGITAP", Boolean.TRUE.equals(r.withinIndia()), r.address(),
                r.pincode(), r.state(), r.district(), r.country(), null);
    }

    /**
     * Two bureau tries, in order: Digitap CRIF then Digitap Experian. Digitap now LEADS the bureau
     * chain in {@code RoutingVerificationPort} and Fintrix (CRIF) sits behind it, so the full chain a
     * borrower can walk is Digitap CRIF → Digitap Experian → Fintrix CRIF.
     *
     * <p>Both legs live inside this one method because the router's provider list is global and maps
     * each id to exactly one adapter — "digitap" cannot appear in the chain twice. Signzy's bureau used
     * to chain {@code experian-lite} → {@code crif} the same way, for the same reason.
     *
     * <p>Why CRIF first within Digitap: it is the same bureau Fintrix serves, so a Fintrix <i>vendor</i>
     * outage still yields the score Fintrix would have returned. Experian stays behind it as a genuinely
     * different data source, which is what rescues a thin-file borrower CRIF has never heard of —
     * dropping it would silently start declining files Experian can currently see.
     *
     * <p>A CRIF no-hit is RETURNED from this method, not retried against Experian: the bureau answered,
     * and a second Digitap pull would be billed for the same bureau. The ROUTER may still carry that
     * no-hit on to Fintrix — that is a different bureau, and the point of the fall-through.
     */
    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        if (featureFlags.isEnabled(CRIF_FLAG, false)) {
            try {
                DigitapDtos.CrifResponse c = crifClient.pull(pan, name, mobile, dob, clientRef);
                return new BureauCheck(c.txnId(), "DIGITAP_CRIF", c.creditScore(), c.noRecord(),
                        null, null, null, null, c.rawResponseJson());
            } catch (VerificationException crifFailed) {
                log.warn("Digitap CRIF leg failed ({}) — falling through to Experian",
                        crifFailed.safeDetail() == null ? crifFailed.getMessage() : crifFailed.safeDetail());
            }
        }
        DigitapDtos.CreditResponse r = creditClient.pull(pan, name, mobile, dob, otp, clientRef);
        BureauReportFacts f = r.facts();
        return new BureauCheck(r.txnId(), "DIGITAP_EXPERIAN", r.creditScore(),
                Boolean.TRUE.equals(r.noRecord()),
                f != null ? f.activeAccounts() : null,
                f != null ? f.defaults() : null,
                f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                f, r.rawResponseJson());
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        throw new CapabilityNotSupportedException("Digitap has no bank penny-drop API in this package");
    }

    @Override
    public EmploymentCheck verifyEmployment(String pan, String mobile, String dob, String employeeName,
                                            String employerName, String uan, String clientRef) {
        DigitapDtos.UanLookupResponse r =
                uanClient.verify(pan, mobile, dob, employeeName, employerName, uan, clientRef);
        boolean found = r.resultCode() != null && r.resultCode() == UAN_RESULT_OK;
        boolean tooMany = r.resultCode() != null && r.resultCode() == UAN_RESULT_TOO_MANY;
        return new EmploymentCheck(r.txnId(), "DIGITAP", found, tooMany, r.message(),
                found && Boolean.TRUE.equals(r.isEmployed()),
                r.uan(), r.uanCount(), trim(r.employerName()), r.establishmentId(), r.memberId(),
                r.dateOfJoining(), r.dateOfExit(),
                r.dateOfExitMarked(), r.leaveReason(), r.uanSource(),
                r.employeeNameMatch(), r.employerNameMatch(), r.employerConfidenceScore(),
                r.isRecent(), r.hasPfFilings(),
                trim(r.nameOnRecord()), r.dobOnRecord(), r.genderOnRecord());
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef) {
        // Digitap Face Match: selfie (person) vs the reference/Aadhaar photo (card).
        DigitapDtos.FaceMatchResponse r = faceMatchClient.match(imageUrl, referenceImageUrl, clientRef);
        boolean live;
        if (referenceImageUrl != null && !referenceImageUrl.isBlank()) {
            // True 1:1 match: the same face, above the confidence threshold, on a non-blurry selfie.
            live = Boolean.TRUE.equals(r.sameFace())
                    && !Boolean.TRUE.equals(r.personImageBlurry())
                    && (r.confidence() == null || r.confidence() >= FACE_CONFIDENCE_THRESHOLD);
        } else {
            // No reference photo → degrade to a single-image quality/face-detection check.
            live = !Boolean.TRUE.equals(r.personImageBlurry());
        }
        return new FaceLivenessCheck(r.txnId(), "DIGITAP", live, r.confidence(), false, r.personImageBlurry());
    }

    @Override
    public LivenessSession livenessInit(String matchImageUrl, String clientRef) {
        throw new CapabilityNotSupportedException("Digitap has no interactive liveness video journey");
    }

    @Override
    public LivenessResultCheck livenessResult(String token) {
        throw new CapabilityNotSupportedException("Digitap has no interactive liveness video journey");
    }

    @Override
    public DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        throw new CapabilityNotSupportedException("Digitap has no DigiLocker consent flow");
    }

    @Override
    public DigiLockerStatus digilockerStatus(String clientId) {
        throw new CapabilityNotSupportedException("Digitap has no DigiLocker consent flow");
    }

    @Override
    public List<DigiLockerDoc> digilockerList(String clientId) {
        throw new CapabilityNotSupportedException("Digitap has no DigiLocker consent flow");
    }

    @Override
    public DigiLockerDownload digilockerDownload(String clientId, String fileId) {
        throw new CapabilityNotSupportedException("Digitap has no DigiLocker consent flow");
    }

    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        throw new CapabilityNotSupportedException("Digitap has no DigiLocker consent flow");
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
