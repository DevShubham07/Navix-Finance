package com.navix.verification.service;

import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.DigitapAddressClient;
import com.navix.verification.client.DigitapCreditClient;
import com.navix.verification.client.DigitapEmailClient;
import com.navix.verification.client.DigitapFaceMatchClient;
import com.navix.verification.client.DigitapPanClient;
import com.navix.verification.dto.DigitapDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps the Digitap clients onto the provider-neutral {@link VerificationPort}. Digitap is the FALLBACK
 * provider (see {@code RoutingVerificationPort}) and the sole provider for email + address (Signzy lacks
 * both). Capabilities Digitap does not offer throw {@link CapabilityNotSupportedException}:
 * <ul>
 *   <li>{@link #pennyDrop} — bank verification is not in the Digitap package handed to NAVIX.</li>
 *   <li>all {@code digilocker*} — Digitap has no DigiLocker consent/OAuth e-Aadhaar flow.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class DigitapVerificationAdapter implements VerificationPort {

    /** Below this face-match confidence the selfie is treated as not-live. */
    private static final double FACE_CONFIDENCE_THRESHOLD = 0.60;

    private final DigitapPanClient panClient;
    private final DigitapEmailClient emailClient;
    private final DigitapAddressClient addressClient;
    private final DigitapCreditClient creditClient;
    private final DigitapFaceMatchClient faceMatchClient;

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        DigitapDtos.PanResponse r = panClient.verify(pan, clientRef);
        return new PanCheck(r.txnId(), "DIGITAP", Boolean.TRUE.equals(r.valid()), trim(r.fullName()),
                r.dob(), r.gender(), Boolean.TRUE.equals(r.aadhaarLinked()), null, pan,
                r.addressState(), r.addressZip());
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        DigitapDtos.EmailResponse r = emailClient.verify(email, individualName, establishmentName, clientRef);
        return new EmailCheck(r.txnId(), "DIGITAP", Boolean.TRUE.equals(r.isVerified()),
                Boolean.TRUE.equals(r.isEstablishmentMatched()), Boolean.TRUE.equals(r.isIndividualMatched()),
                Boolean.TRUE.equals(r.isGenericEmail()), r.matchedEstablishment());
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        DigitapDtos.AddressResponse r = addressClient.verify(latitude, longitude, clientRef);
        return new AddressCheck(r.code(), "DIGITAP", Boolean.TRUE.equals(r.withinIndia()), r.address(),
                r.pincode(), r.state(), r.district());
    }

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String clientRef) {
        DigitapDtos.CreditResponse r = creditClient.pull(pan, name, mobile, dob, clientRef);
        BureauReportFacts f = r.facts();
        return new BureauCheck(r.txnId(), "DIGITAP_EXPERIAN", r.creditScore(),
                Boolean.TRUE.equals(r.noRecord()),
                f != null ? f.activeAccounts() : null,
                f != null ? f.defaults() : null,
                f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                f);
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        throw new CapabilityNotSupportedException("Digitap has no bank penny-drop API in this package");
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
        return new FaceLivenessCheck(r.txnId(), "DIGITAP", live, r.confidence(), false);
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
