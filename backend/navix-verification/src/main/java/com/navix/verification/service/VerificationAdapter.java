package com.navix.verification.service;

import com.navix.common.verification.VerificationPort;
import com.navix.verification.client.AddressVerificationClient;
import com.navix.verification.client.DigiLockerClient;
import com.navix.verification.client.EmailVerificationClient;
import com.navix.verification.client.FaceLivenessClient;
import com.navix.verification.client.PanComprehensiveClient;
import com.navix.verification.client.PennyDropClient;
import com.navix.verification.dto.DigiLockerDtos;
import com.navix.verification.dto.FintrixDtos;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Adapter mapping the Fintrix/DigiLocker clients + {@link BureauService} onto the
 * provider-neutral {@link VerificationPort} consumed by the loan aggregate. This is
 * the single place provider DTOs touch — nothing Fintrix/DigiLocker-shaped crosses
 * onto the loan classpath. Wired by component scan from {@code navix-app}.
 */
@Component
@RequiredArgsConstructor
public class VerificationAdapter implements VerificationPort {

    private final PanComprehensiveClient panClient;
    private final EmailVerificationClient emailClient;
    private final AddressVerificationClient addressClient;
    private final PennyDropClient pennyDropClient;
    private final FaceLivenessClient faceLivenessClient;
    private final DigiLockerClient digiLockerClient;
    private final BureauService bureauService;

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        FintrixDtos.PanResponse r = panClient.verify(pan, clientRef);
        return new PanCheck(r.txnId(), "valid".equalsIgnoreCase(r.status()),
                trim(r.fullName()), r.dob(), r.gender(), bool(r.aadhaarLinked()),
                r.maskedAadhaar(), r.panNumber(), r.addressState(), r.addressZip());
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        FintrixDtos.EmailVerificationResponse r = emailClient.verify(email, individualName, establishmentName, clientRef);
        return new EmailCheck(r.txnId(), bool(r.isVerified()), bool(r.isEstablishmentMatched()),
                bool(r.isIndividualMatched()), bool(r.isGenericEmail()), r.matchedEstablishment());
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        FintrixDtos.AddressVerificationResponse r = addressClient.verify(latitude, longitude, clientRef);
        return new AddressCheck(r.code(), bool(r.withinIndia()), r.address(), r.pincode(), r.state(), r.district());
    }

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String clientRef) {
        BureauService.UnifiedBureauReport r = bureauService.pull(pan, name, mobile, dob, clientRef);
        return new BureauCheck(r.txnId(), r.source(), r.score(), r.noRecord(),
                r.activeAccounts(), r.overdueAccounts(), r.totalBalance(), r.facts());
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        FintrixDtos.PennyDropResponse r = pennyDropClient.verify(accountNumber, ifsc, clientRef);
        FintrixDtos.IfscDetails d = r.ifscDetails();
        return new PennyDropCheck(r.txnId(), bool(r.accountExists()), r.fullName(),
                d != null ? d.bank() : null, d != null ? d.ifsc() : null);
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String clientRef) {
        FintrixDtos.FaceLivenessResponse r = faceLivenessClient.check(imageUrl, clientRef);
        return new FaceLivenessCheck(r.txnId(), bool(r.isLive()), r.livenessConfidence(),
                bool(r.multipleFaceDetected()));
    }

    @Override
    public DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        DigiLockerDtos.InitializeResponse r = digiLockerClient.initialize(redirectUrl, expiryMinutes, signupFlow);
        return new DigiLockerSession(r.txnId(), r.clientId(), r.url(), r.expirySeconds());
    }

    @Override
    public DigiLockerStatus digilockerStatus(String clientId) {
        DigiLockerDtos.StatusResponse r = digiLockerClient.status(clientId);
        return new DigiLockerStatus(r.txnId(), r.status(), bool(r.completed()), bool(r.failed()),
                bool(r.aadhaarLinked()));
    }

    @Override
    public List<DigiLockerDoc> digilockerList(String clientId) {
        DigiLockerDtos.ListDocumentsResponse r = digiLockerClient.listDocuments(clientId);
        if (r == null || r.documents() == null) {
            return List.of();
        }
        return r.documents().stream()
                .map(d -> new DigiLockerDoc(d.fileId(), d.name(), d.docType(), d.fileType()))
                .toList();
    }

    @Override
    public DigiLockerDownload digilockerDownload(String clientId, String fileId) {
        DigiLockerDtos.DocumentResponse r = digiLockerClient.document(clientId, fileId);
        return new DigiLockerDownload(r.txnId(), r.downloadUrl(), r.mimeType());
    }

    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        DigiLockerDtos.AadhaarXmlResponse r = digiLockerClient.aadhaarXml(clientId);
        return new AadhaarResult(r.txnId(), trim(r.fullName()), r.dob(), r.gender(), r.maskedAadhaar(),
                r.fullAddress(), r.state(), r.pincode(), r.profileImageBase64(), r.xmlUrl());
    }

    private static boolean bool(Boolean b) {
        return Boolean.TRUE.equals(b);
    }

    private static String trim(String s) {
        return s == null ? null : s.trim();
    }
}
