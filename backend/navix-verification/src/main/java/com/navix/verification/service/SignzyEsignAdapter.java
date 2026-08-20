package com.navix.verification.service;

import com.navix.common.verification.EsignPort;
import com.navix.verification.client.SignzyContractClient;
import com.navix.verification.config.EsignProperties;
import com.navix.verification.dto.SignzyDtos.ContractInitiateRequest;
import com.navix.verification.dto.SignzyDtos.ContractInitiateResponse;
import com.navix.verification.dto.SignzyDtos.ContractPullResponse;
import com.navix.verification.dto.SignzyDtos.ContractSignaturePlacement;
import com.navix.verification.dto.SignzyDtos.ContractSigner;
import com.navix.verification.dto.SignzyDtos.EmudhraCustomization;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.support.ProviderCall;
import com.navix.verification.support.ProviderCallLog;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Signzy Contract eSign behind the {@link EsignPort} seam — Aadhaar eSign (eMudhra OTP) of the sanction
 * letter.
 *
 * <p>A redirect journey, so the seam's two halves are genuinely two round trips: {@link #initiate} mints
 * the contract and hands back eMudhra's hosted {@code esignUrl}, and {@link #fetch} polls until the
 * borrower has finished. The provider's own status is advisory — the caller's DB row is authoritative,
 * the same rule DigiLocker taught us.
 *
 * <p><b>Identity matching.</b> When the borrower's Aadhaar demographics are on file we ask Signzy to
 * reject a signature made with somebody else's Aadhaar (name + year of birth + gender). Those fields come
 * from DigiLocker, which is deliberately non-blocking, so when they are absent we degrade to matching on
 * the name alone rather than refuse to let the borrower sign. Signzy only honours the YOB/gender flags
 * when a threshold is sent, and rejects a null threshold outright — so the trio is sent or omitted whole.
 */
@Slf4j
@RequiredArgsConstructor
public class SignzyEsignAdapter implements EsignPort {

    public static final String PROVIDER = "SIGNZY";

    /** Aadhaar eSign with an OTP to the Aadhaar-registered mobile. */
    private static final String SIGNATURE_TYPE = "AADHAARESIGN-OTP";
    private static final String ESIGN_PROVIDER = "EMUDHRA";
    /** Synthetic endpoint label for the signed-copy fetch, which has no fixed provider path. */
    private static final String SIGNED_CONTRACT_DOWNLOAD = "/signzy/contract/signed-copy";

    private final SignzyContractClient client;
    private final EsignProperties props;

    @Override
    public EsignSession initiate(EsignRequest request) {
        Signer signer = request.signer();
        boolean strict = hasText(signer.gender()) && hasText(signer.yearOfBirth());

        ContractSigner contractSigner = new ContractSigner(
                signer.name(),
                SIGNATURE_TYPE,
                strict ? signer.gender() : null,
                strict ? signer.yearOfBirth() : null,
                blankToNull(signer.uidLastFourDigits()),
                List.of(new ContractSignaturePlacement(List.of("All"), List.of("BottomLeft"))));

        ContractInitiateResponse response = client.initiate(new ContractInitiateRequest(
                request.documentUrl(),
                request.contractName(),
                request.executerName() != null ? request.executerName() : props.executerName(),
                request.successRedirectUrl(),
                request.failureRedirectUrl(),
                props.callbackUrl(),
                blankToNull(props.callbackSecret()),
                ESIGN_PROVIDER,
                customization(),
                List.of(contractSigner),
                props.nameMatchThreshold(),
                strict ? Boolean.TRUE : null,
                strict ? Boolean.TRUE : null));

        if (!hasText(response.contractId()) || !hasText(response.esignUrl())) {
            throw new VerificationException("Signzy returned no contract id / esign URL");
        }
        // Never log the signer's name or the document URL — the URL is a presigned handle on the KFS.
        log.info("Signzy eSign contract {} opened (ref {}, identity match {})",
                response.contractId(), request.clientRef(), strict ? "strict" : "name-only");
        return new EsignSession(sessionId(response.contractId(), response.signerId()),
                response.esignUrl(), PROVIDER);
    }

    @Override
    public EsignResult fetch(String sessionId) {
        String contractId = contractIdOf(sessionId);
        ContractPullResponse c = client.pullData(contractId);

        if (!c.found()) {
            return incomplete(contractId, CONTRACT_EXPIRED);
        }
        if (!c.completed()) {
            return incomplete(contractId, null);
        }
        boolean signed = c.signedSignerCount() != null && c.signedSignerCount() > 0
                && hasText(c.finalSignedContract());
        if (!signed) {
            return new EsignResult(true, false, null, null, contractId, PROVIDER,
                    reasonFor(c));
        }
        return new EsignResult(true, true, parseCompletionTime(c.contractCompletionTime()),
                download(c.finalSignedContract()), contractId, PROVIDER, null);
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * Signzy keys the contract and the signer separately, but the port resolves a session by a single
     * handle — so both ride in one opaque string.
     */
    private static String sessionId(String contractId, String signerId) {
        return signerId == null ? contractId : contractId + "|" + signerId;
    }

    private static String contractIdOf(String sessionId) {
        int sep = sessionId.indexOf('|');
        return sep < 0 ? sessionId : sessionId.substring(0, sep);
    }

    private EmudhraCustomization customization() {
        if (!hasText(props.logoUrl()) && !hasText(props.headerColour()) && !hasText(props.buttonColour())) {
            return null;
        }
        return new EmudhraCustomization(blankToNull(props.logoUrl()),
                props.headerColour(), props.buttonColour());
    }

    private static EsignResult incomplete(String contractId, String reason) {
        return new EsignResult(false, false, null, null, contractId, PROVIDER, reason);
    }

    /** Why a finished contract carries no signature — a name mismatch is the common case. */
    private static String reasonFor(ContractPullResponse c) {
        if (hasText(c.signerErrorMessage())) {
            return c.signerErrorMessage();
        }
        if (hasText(c.nameMatchResult())) {
            return "Name match: " + c.nameMatchResult();
        }
        return hasText(c.signerStatus()) ? "Signer status: " + c.signerStatus() : "Signature not captured";
    }

    /**
     * Signzy serves the signed copy from a short-lived persist URL, so it is pulled down immediately
     * rather than stored as a link.
     */
    private static byte[] download(String url) {
        // Audited like every other provider call, but by METADATA only: the response is a PDF, and a
        // megabyte of base64 in the audit table would cost a lot and diagnose nothing.
        long started = System.nanoTime();
        try {
            HttpResponse<byte[]> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build()
                    .send(HttpRequest.newBuilder()
                                    .uri(URI.create(url))
                                    .timeout(Duration.ofSeconds(30))
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            byte[] body = response.body();
            if (status / 100 != 2) {
                recordDownload(started, status, body == null ? 0 : body.length,
                        "Signed contract fetch failed: HTTP " + status);
                throw new VerificationException("Signed contract fetch failed: HTTP " + status);
            }
            recordDownload(started, status, body == null ? 0 : body.length, null);
            return body;
        } catch (IOException e) {
            recordDownload(started, null, 0, e.toString());
            throw new VerificationException("Signed contract fetch failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            recordDownload(started, null, 0, e.toString());
            throw new VerificationException("Signed contract fetch interrupted", e);
        }
    }

    /** The persist URL is single-use and short-lived, so it is not worth storing — only the outcome is. */
    private static void recordDownload(long startedNanos, Integer httpStatus, int bytes, String error) {
        ProviderCallLog.record(new ProviderCall(
                "SIGNZY", "ESIGN", SIGNED_CONTRACT_DOWNLOAD,
                "{\"note\":\"signed contract download\"}",
                "{\"bytes\":" + bytes + "}", httpStatus,
                (System.nanoTime() - startedNanos) / 1_000_000L,
                error == null ? ProviderCall.SUCCESS : ProviderCall.FAILED, error));
    }

    /**
     * Signzy formats the completion time for humans and not to a fixed pattern, so a failure to read it
     * must not lose an otherwise good signature — we fall back to "now".
     */
    private static Instant parseCompletionTime(String value) {
        if (!hasText(value)) {
            return Instant.now();
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException notIso) {
            log.debug("Unparseable contract completion time {} — using now()", value);
            return Instant.now();
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }

    private static String blankToNull(String s) {
        return hasText(s) ? s : null;
    }
}
