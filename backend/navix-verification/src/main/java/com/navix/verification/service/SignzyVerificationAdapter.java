package com.navix.verification.service;

import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.SignzyBankVerificationClient;
import com.navix.verification.client.SignzyCrifClient;
import com.navix.verification.client.SignzyDigiLockerClient;
import com.navix.verification.client.SignzyExperianClient;
import com.navix.verification.client.SignzyGeocodeClient;
import com.navix.verification.client.SignzyPanClient;
import com.navix.verification.dto.SignzyDtos;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Maps the Signzy clients onto the provider-neutral {@link VerificationPort}. Signzy is the PRIMARY
 * provider (see {@code RoutingVerificationPort}). Capabilities Signzy does not offer throw
 * {@link CapabilityNotSupportedException} so the router skips to Digitap:
 * <ul>
 *   <li>{@link #verifyEmail} — Signzy has no email API (Digitap only).</li>
 *   <li>{@link #faceLiveness} — Signzy Liveness Secure is an interactive iframe flow, not the
 *       synchronous single-image liveness this port method expects; the router uses Digitap Face Match.
 *       ({@code SignzyLivenessClient} remains available for a future async selfie journey.)</li>
 * </ul>
 * {@link #verifyAddress} uses Signzy reverse-geocoding (production account); Digitap is the fallback.
 * PAN 206AB also runs on the production account (unmasked name). Penny-drop and the full DigiLocker
 * consent flow are Signzy-only (Digitap lacks them).
 */
@Component
@RequiredArgsConstructor
public class SignzyVerificationAdapter implements VerificationPort {

    private final SignzyPanClient panClient;
    private final SignzyBankVerificationClient bankClient;
    private final SignzyExperianClient experianClient;
    private final SignzyCrifClient crifClient;
    private final SignzyDigiLockerClient digiLockerClient;
    private final SignzyGeocodeClient geocodeClient;

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        SignzyDtos.PanResponse r = panClient.verify(pan);
        boolean operative = "operative".equalsIgnoreCase(r.panStatus());
        boolean aadhaarLinked = "linked".equalsIgnoreCase(r.panAadhaarLinkStatus());
        // Prefer the unmasked name (maskedName=false); fall back to the masked entityName. Signzy 206AB
        // returns no DOB/gender/address — those come from DigiLocker.
        String name = !isBlank(r.unMaskedName()) ? r.unMaskedName() : r.entityName();
        return new PanCheck(r.txnId(), "SIGNZY", operative, trim(name), null, null,
                aadhaarLinked, null, r.number(), null, null);
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        throw new CapabilityNotSupportedException("Signzy has no email verification API");
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        // Signzy reverse-geocode (production account). Router falls back to Digitap on any failure.
        SignzyDtos.GeocodeResponse g = geocodeClient.reverseGeocode(latitude, longitude);
        boolean withinIndia = "IN".equalsIgnoreCase(g.countryCode());
        return new AddressCheck(clientRef, "SIGNZY", withinIndia, g.address(), g.zipcode(),
                g.state(), g.city());
    }

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String clientRef) {
        // Experian PRIMARY → CRIF FALLBACK, mirroring the retired BureauService.
        try {
            SignzyDtos.ExperianResponse e = experianClient.pull(pan, name, mobile, dob);
            if (e != null && e.creditScore() != null && !Boolean.TRUE.equals(e.noRecord())) {
                BureauReportFacts f = e.facts();
                return new BureauCheck(e.txnId(), "SIGNZY_EXPERIAN", e.creditScore(), false,
                        f != null ? f.activeAccounts() : null,
                        f != null ? f.defaults() : null,
                        f != null && f.totalBalanceRupees() != null ? f.totalBalanceRupees().doubleValue() : null,
                        f);
            }
        } catch (VerificationException experianMiss) {
            // fall through to CRIF
        }
        SignzyDtos.CrifResponse c = crifClient.pull(pan, name, mobile, dob);
        return new BureauCheck(c.txnId(), "SIGNZY_CRIF", c.score(), c.score() == null,
                null, null, null, null);
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        SignzyDtos.BankVerificationResponse r = bankClient.verify(accountNumber, ifsc, null);
        return new PennyDropCheck(r.txnId(), "SIGNZY", Boolean.TRUE.equals(r.active()), r.beneName(),
                null, r.beneIfsc());
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef) {
        throw new CapabilityNotSupportedException(
                "Signzy Liveness Secure is an interactive iframe flow, not synchronous image face-match");
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

    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        try {
            SignzyDtos.AadhaarResponse a = digiLockerClient.getEAadhaar(clientId);
            // Gate on a valid Aadhaar document-signer signature; without it, treat as not-ready.
            if (!Boolean.TRUE.equals(a.validDsc()) || isBlank(a.fullName())) {
                return notReady(clientId);
            }
            // Carry the Aadhaar face-photo URL in profileImageBase64 (Signzy returns a persist URL, not
            // base64) so the selfie step can face-match the borrower's selfie against it.
            return new AadhaarResult(a.txnId(), trim(a.fullName()), a.dob(), a.gender(), a.maskedUid(),
                    a.fullAddress(), a.state(), a.pincode(), a.photoUrl(), a.xmlUrl());
        } catch (VerificationException notReadyYet) {
            // Consent not completed / XML not materialised yet — surface blank demographics so the
            // caller's readiness gate throws DIGILOCKER_NOT_READY and the bounded retry kicks in.
            return notReady(clientId);
        }
    }

    private static AadhaarResult notReady(String clientId) {
        return new AadhaarResult(clientId, null, null, null, null, null, null, null, null, null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
