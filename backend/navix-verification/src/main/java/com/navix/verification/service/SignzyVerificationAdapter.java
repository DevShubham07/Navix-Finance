package com.navix.verification.service;

import static com.navix.verification.support.ProviderJson.ref;

import com.navix.common.verification.ProviderFailureDetails;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.SignzyBankVerificationClient;
import com.navix.verification.client.SignzyDigiLockerClient;
import com.navix.verification.client.SignzyEmailClient;
import com.navix.verification.client.SignzyGeocodeClient;
import com.navix.verification.client.SignzyLivenessClient;
import com.navix.verification.client.SignzyPanClient;
import com.navix.verification.dto.SignzyDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.TerminalVerificationException;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps the Signzy clients onto the provider-neutral {@link VerificationPort}. Signzy is the PRIMARY
 * provider (see {@code RoutingVerificationPort}). Capabilities Signzy does not offer throw
 * {@link CapabilityNotSupportedException} so the router skips to Digitap:
 * <ul>
 *   <li>{@link #faceLiveness} — Signzy Liveness Secure is an interactive iframe flow, not the
 *       synchronous single-image liveness this port method expects; the router uses Digitap Face Match.
 *       ({@code SignzyLivenessClient} remains available for a future async selfie journey.)</li>
 *   <li>{@link #pullBureau} — RETIRED from routing: Fintrix ({@code FintrixVerificationAdapter}) is now
 *       the bureau primary and Digitap Credit Analytics the fallback. {@code SignzyExperianClient} /
 *       {@code SignzyCrifClient} are kept as beans purely for the ADMIN provider workbench
 *       ({@code ProviderApiWorkbenchService}), which dispatches them directly — not through this
 *       adapter — so this class no longer needs them as fields.</li>
 * </ul>
 * {@link #verifyEmail} (Email Verification V2), {@link #verifyAddress} (reverse-geocoding) and PAN 206AB
 * all run on the production account; Digitap is the fallback for each. Penny-drop and the full DigiLocker
 * consent flow are Signzy-only (Digitap lacks them).
 */
@Component
@RequiredArgsConstructor
public class SignzyVerificationAdapter implements VerificationPort {

    private final SignzyPanClient panClient;
    private final SignzyBankVerificationClient bankClient;
    private final SignzyDigiLockerClient digiLockerClient;
    private final SignzyGeocodeClient geocodeClient;
    private final SignzyEmailClient emailClient;
    private final SignzyLivenessClient livenessClient;

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        SignzyDtos.PanResponse r = panClient.verify(pan);
        boolean operative = "operative".equalsIgnoreCase(r.panStatus());
        boolean aadhaarLinked = "linked".equalsIgnoreCase(r.panAadhaarLinkStatus());
        // Prefer the unmasked name (maskedName=false); fall back to the masked entityName. Signzy 206AB
        // returns no DOB/gender/address — those come from DigiLocker.
        String name = !isBlank(r.unMaskedName()) ? r.unMaskedName() : r.entityName();
        return new PanCheck(r.txnId(), "SIGNZY", operative, trim(name), null, null,
                aadhaarLinked, null, r.number(), null, null,
                r.panStatus(), r.panAllotmentDate(), r.compliant(), r.isSpecified());
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        // Signzy Email V2 (production account). The router falls back to Digitap only if this throws.
        SignzyDtos.EmailV2Response r = emailClient.verify(email);
        boolean verified = Boolean.TRUE.equals(r.validEmail());
        // Signzy V2 takes only the email; derive name-matches from its person/company enrichment.
        boolean individualMatched = nameMatches(individualName, r.personName());
        boolean establishmentMatched = nameMatches(establishmentName, r.companyName());
        return new EmailCheck(ref(clientRef), "SIGNZY", verified,
                establishmentMatched, individualMatched,
                Boolean.TRUE.equals(r.freeEmail()), r.companyName(),
                r.status(), r.domain(), r.mxFound(), r.mxRecord(), r.smtpProvider(),
                r.didYouMean(), r.personName(), r.companyName(), null);
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        // Signzy reverse-geocode (production account). Router falls back to Digitap on any failure.
        SignzyDtos.GeocodeResponse g = geocodeClient.reverseGeocode(latitude, longitude);
        boolean withinIndia = "IN".equalsIgnoreCase(g.countryCode());
        return new AddressCheck(clientRef, "SIGNZY", withinIndia, g.address(), g.zipcode(),
                g.state(), g.city(), g.countryCode(), g.confidenceScore());
    }

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        // Retired from routing — Digitap Credit Analytics is now the bureau primary and Fintrix the
        // fallback (see the class javadoc). Throwing here — rather than still calling Experian/CRIF —
        // is the only way to drop Signzy's bureau role while it stays in the chain for its other seven
        // capabilities (the chain property is global, not per-capability).
        throw new CapabilityNotSupportedException(
                "Signzy bureau (Experian/CRIF) retired from routing — Digitap is now primary");
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        SignzyDtos.BankVerificationResponse r = bankClient.verify(accountNumber, ifsc, null);
        return new PennyDropCheck(r.txnId(), "SIGNZY", Boolean.TRUE.equals(r.active()), r.beneName(),
                r.bankName(), r.beneIfsc() != null ? r.beneIfsc() : ifsc,
                r.bankRrn(), r.reason(), r.nameMatch());
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef) {
        throw new CapabilityNotSupportedException(
                "Signzy Liveness Secure is an interactive video flow — use livenessInit/livenessResult, "
                        + "not synchronous image face-match");
    }

    @Override
    public EmploymentCheck verifyEmployment(String pan, String mobile, String dob, String employeeName,
                                            String employerName, String uan, String clientRef) {
        throw new CapabilityNotSupportedException("Signzy has no UAN/EPFO employment lookup");
    }

    @Override
    public LivenessSession livenessInit(String matchImageUrl, String clientRef) {
        // Interactive video journey (production account). matchImageUrl = the Aadhaar face for 1:1 match.
        SignzyDtos.LivenessSession s = livenessClient.createUrl(matchImageUrl);
        return new LivenessSession(s.token(), "SIGNZY", s.consumerId(), s.videoUrl());
    }

    @Override
    public LivenessResultCheck livenessResult(String token) {
        SignzyDtos.LivenessResult r = livenessClient.getData(token);
        return new LivenessResultCheck(token, "SIGNZY", r.completed(),
                Boolean.TRUE.equals(r.live()), r.livenessScore(), r.faceVerified(), r.matchPercentage(),
                r.capturedImage(), Boolean.TRUE.equals(r.overallStatus()));
    }

    @Override
    public DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        SignzyDtos.DigiLockerSession r = digiLockerClient.createUrl(redirectUrl, signupFlow);
        return new DigiLockerSession(r.txnId(), r.requestId(), r.url(), expiryMinutes * 60);
    }

    @Override
    public DigiLockerStatus digilockerStatus(String clientId) {
        // Signzy completion is REDIRECT-driven; the poll is advisory only (our DB AADHAAR row is the
        // source of truth). Report not-completed so the caller waits for the consent callback.
        return new DigiLockerStatus(clientId, "client_initiated", false, false, false);
    }

    @Override
    public List<DigiLockerDoc> digilockerList(String clientId) {
        // Signzy delivers the Aadhaar document via get-eAadhaar / the consent callback, not a separate
        // list endpoint. Returning empty makes the caller skip the (optional) raw-PDF S3 ingest.
        return List.of();
    }

    @Override
    public DigiLockerDownload digilockerDownload(String clientId, String fileId) {
        // Not reached in practice (digilockerList is empty). fileId, if ever supplied, is a persist URL.
        return new DigiLockerDownload(clientId, fileId, "application/pdf");
    }

    /**
     * Fetch the signed e-Aadhaar, distinguishing the four things Signzy's failures actually mean.
     *
     * <p>This used to map <b>every</b> exception — and any document whose signature did not validate —
     * onto "not ready", which the caller turns into a retryable {@code DIGILOCKER_NOT_READY} and the
     * borrower's page polls 45 times at four-second intervals. Three different verdicts were hidden
     * behind that one answer in the Sep-2026 audit: a DigiLocker outage polled 183 times in twenty
     * minutes, a borrower who had explicitly <i>declined</i> consent polled 23 times, and an Aadhaar
     * whose document-signer signature was invalid fetched successfully 820 times in an afternoon.
     *
     * <p>Only two things are genuinely "come back in a moment": a 401 (consent not finished) and a
     * transport blip. Everything else now says what it is.
     */
    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        SignzyDtos.AadhaarResponse a;
        try {
            a = digiLockerClient.getEAadhaar(clientId);
        } catch (TerminalVerificationException alreadyClassified) {
            throw alreadyClassified;
        } catch (VerificationException e) {
            Integer status = e.httpStatus();
            String detail = e.safeDetail() == null ? "" : e.safeDetail().toLowerCase(Locale.ROOT);
            if (status == null || status == 401) {
                // No response at all, or consent genuinely still in flight. Keep polling.
                return notReady(clientId);
            }
            if (status == 409 && detail.contains("upstream")) {
                throw new TerminalVerificationException("DigiLocker upstream is down", e, status,
                        e.endpoint(), ProviderFailureDetails.DIGILOCKER_UPSTREAM_DOWN, e.safeDetail());
            }
            if (status == 400 && detail.contains("denied")) {
                throw new TerminalVerificationException("Borrower declined the DigiLocker consent", e,
                        status, e.endpoint(), ProviderFailureDetails.DIGILOCKER_CONSENT_DENIED,
                        e.safeDetail());
            }
            throw e;
        }
        if (isBlank(a.fullName())) {
            // Consent is through but the XML has not materialised — the one case the poll is for.
            return notReady(clientId);
        }
        // The signature verdict travels with the result instead of erasing it: an invalid signature is
        // a real, complete document that a human has to look at, not an absence of one.
        // Carry the Aadhaar face-photo URL in profileImageBase64 (Signzy returns a persist URL, not
        // base64) so the selfie step can face-match the borrower's selfie against it.
        return new AadhaarResult(a.txnId(), trim(a.fullName()), a.dob(), a.gender(), a.maskedUid(),
                a.fullAddress(), a.state(), a.district(), a.city(), a.pincode(), a.country(),
                a.addressLine(), a.landmark(), a.dscSubject(), a.photoUrl(), a.pdfUrl(), a.jpegUrl(),
                a.xmlUrl(), a.validDsc());
    }

    private static AadhaarResult notReady(String clientId) {
        return new AadhaarResult(clientId, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }

    /**
     * Loose name match for the email enrichment: true only when both sides are present and one
     * normalised (upper-cased, whitespace-collapsed) name contains the other. Signzy V2 does not take a
     * name input, so this is a best-effort signal derived from its person/company enrichment.
     */
    private static boolean nameMatches(String provided, String resolved) {
        if (isBlank(provided) || isBlank(resolved)) {
            return false;
        }
        String a = provided.trim().toUpperCase().replaceAll("\\s+", " ");
        String b = resolved.trim().toUpperCase().replaceAll("\\s+", " ");
        return a.contains(b) || b.contains(a);
    }
}
