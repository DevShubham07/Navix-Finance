package com.navix.verification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.ProviderFailureDetails;
import com.navix.common.verification.VerificationPort.AadhaarResult;
import com.navix.verification.client.SignzyBankVerificationClient;
import com.navix.verification.client.SignzyDigiLockerClient;
import com.navix.verification.client.SignzyEmailClient;
import com.navix.verification.client.SignzyGeocodeClient;
import com.navix.verification.client.SignzyLivenessClient;
import com.navix.verification.client.SignzyPanClient;
import com.navix.verification.dto.SignzyDtos.AadhaarResponse;
import com.navix.verification.exception.TerminalVerificationException;
import com.navix.verification.exception.VerificationException;
import com.navix.verification.support.VendorEnvelopes;
import org.junit.jupiter.api.Test;

/**
 * The DigiLocker e-Aadhaar fetch inside the Signzy adapter — specifically, the four different things a
 * failed {@code geteAadhaar} can mean and which of them the borrower's page is allowed to keep polling.
 *
 * <p>Every one of them used to collapse into a single "not ready" result, which
 * {@code ApplicationVerificationService} turns into a retryable {@code DIGILOCKER_NOT_READY} that the
 * frontend polls 45 times at four-second intervals. The Sep-2026 provider audit found three distinct
 * verdicts buried under it: a DigiLocker outage polled 183 times in twenty minutes, a borrower who had
 * explicitly declined consent polled 23 times, and an Aadhaar whose document-signer signature was
 * invalid fetched <i>successfully</i> 820 times across 62 sessions in one afternoon because a
 * complete, readable document was being reported as an absent one.
 *
 * <p>The client is mocked: what is under test is the classification, not the HTTP call. The failure
 * envelopes are the real redacted production bodies from {@code vendor-envelopes/}, and the
 * {@code safeDetail} the adapter branches on is extracted from them exactly as {@code ProviderJson}
 * does — {@code findValue("message")} — so a vendor changing its wording breaks this test rather than
 * silently restoring the 183-poll behaviour.
 */
class SignzyVerificationAdapterTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final SignzyPanClient panClient = mock(SignzyPanClient.class);
    private final SignzyBankVerificationClient bankClient = mock(SignzyBankVerificationClient.class);
    private final SignzyDigiLockerClient digiLockerClient = mock(SignzyDigiLockerClient.class);
    private final SignzyGeocodeClient geocodeClient = mock(SignzyGeocodeClient.class);
    private final SignzyEmailClient emailClient = mock(SignzyEmailClient.class);
    private final SignzyLivenessClient livenessClient = mock(SignzyLivenessClient.class);

    private final SignzyVerificationAdapter adapter = new SignzyVerificationAdapter(
            panClient, bankClient, digiLockerClient, geocodeClient, emailClient, livenessClient);

    private static final String ENDPOINT = "/api/v3/digilocker-v2/geteAadhaar";

    /**
     * The redacted vendor message the adapter actually branches on, pulled out of the fixture the same
     * way {@code ProviderJson.safeDiagnostic} does (the first of message/error_message/... found
     * anywhere in the body — these envelopes nest it under {@code error}).
     */
    private static String vendorMessage(String fixture) {
        try {
            JsonNode found = JSON.readTree(VendorEnvelopes.load(fixture)).findValue("message");
            assertThat(found).as("fixture %s carries no message field", fixture).isNotNull();
            return found.asText();
        } catch (Exception unparseable) {
            throw new AssertionError("Could not read " + fixture, unparseable);
        }
    }

    private static VerificationException signzyFailure(Integer status, String safeDetail) {
        return new VerificationException("Signzy geteAadhaar failed", null, status, ENDPOINT,
                null, safeDetail);
    }

    private static AadhaarResponse aadhaar(Boolean validDsc) {
        return new AadhaarResponse("REQ-DL-1", "  EXAMPLE BORROWER  ", "XXXXXXXX1234", "1994-06-11",
                "M", validDsc, "Example Document Signer",
                "12 Example Road, Example Colony, Example City, Example State - 110001",
                "Example State", "Example District", "Example City", "110001", "India",
                "12 Example Road, Example Colony", "Near Example Park",
                "https://example.invalid/photo.jpg", "https://example.invalid/aadhaar.pdf",
                "https://example.invalid/aadhaar.jpg", "https://example.invalid/eaadhaar.xml");
    }

    /**
     * The one failure that genuinely IS "come back in a moment". Signzy answers 401 for the whole
     * window between minting the consent URL and the borrower finishing DigiLocker, so narrowing the
     * classification must not narrow this: a 401 that started throwing would turn every in-progress
     * consent into a hard KYC failure seconds after the borrower clicked through.
     */
    @Test
    void aadhaar401IsNotReady() {
        when(digiLockerClient.getEAadhaar(anyString()))
                .thenThrow(signzyFailure(401, "Authorization Required"));

        AadhaarResult r = adapter.digilockerAadhaar("REQ-DL-1");

        assertThat(r.txnId()).isEqualTo("REQ-DL-1");
        assertThat(r.fullName()).isNull();
        assertThat(r.validDsc()).isNull();
    }

    /**
     * A read timeout on {@code geteAadhaar} has always been tolerated, and must stay tolerated. It
     * arrives as a {@code VerificationException} with NO http status — that is how
     * {@code ProviderJson.post} wraps a transport failure — and the borrower has done nothing wrong,
     * so the poll continues. Treating a null status as "unclassified, therefore rethrow" would fail a
     * KYC on one slow packet.
     */
    @Test
    void aadhaarWithNoHttpStatusIsNotReady() {
        when(digiLockerClient.getEAadhaar(anyString()))
                .thenThrow(new VerificationException("Transport failure calling " + ENDPOINT,
                        new java.net.SocketTimeoutException("Read timed out"), null, ENDPOINT, null, null));

        AadhaarResult r = adapter.digilockerAadhaar("REQ-DL-1");

        assertThat(r.fullName()).isNull();
        assertThat(r.validDsc()).isNull();
    }

    /**
     * DigiLocker itself being down is not the borrower's consent being slow. One such outage was polled
     * 183 times in twenty minutes for a single applicant because the 409 looked identical to a 401 by
     * the time it reached the caller. Classified, it becomes a terminal answer the caller can back off
     * from — retryable, but on an outage cadence, not a five-second one.
     */
    @Test
    void aadhaar409UpstreamDownIsClassified() {
        when(digiLockerClient.getEAadhaar(anyString())).thenThrow(
                signzyFailure(409, vendorMessage("signzy-digilocker-409-upstream-down.json")));

        assertThatThrownBy(() -> adapter.digilockerAadhaar("REQ-DL-1"))
                .isInstanceOf(TerminalVerificationException.class)
                .extracting(e -> ((TerminalVerificationException) e).providerCode())
                .isEqualTo(ProviderFailureDetails.DIGILOCKER_UPSTREAM_DOWN);
    }

    /**
     * The borrower pressed "Deny" in DigiLocker. No amount of polling can change that answer, and 23
     * polls were spent on one such session — the consent has to be started afresh instead. Signzy
     * reports it as a 400 {@code AUTH_FAIL}, which is indistinguishable from a request-shape error
     * until the message is read.
     */
    @Test
    void aadhaar400UserDeniedIsClassified() {
        when(digiLockerClient.getEAadhaar(anyString())).thenThrow(
                signzyFailure(400, vendorMessage("signzy-digilocker-400-consent-denied.json")));

        assertThatThrownBy(() -> adapter.digilockerAadhaar("REQ-DL-1"))
                .isInstanceOf(TerminalVerificationException.class)
                .extracting(e -> ((TerminalVerificationException) e).providerCode())
                .isEqualTo(ProviderFailureDetails.DIGILOCKER_CONSENT_DENIED);
    }

    /**
     * THE 820-FETCH CASE. One borrower's e-Aadhaar came back complete and readable with
     * {@code x509Data.validAadhaarDSC = "no"} (see {@code signzy-digilocker-200-dsc-no.json}); because
     * an invalid signature was folded into "not ready", the page opened 62 DigiLocker sessions and made
     * 820 successful, billable document fetches in three and a half hours and still never finished KYC.
     *
     * <p>An invalid signature is a real document a human has to look at, not an absent one — so the
     * demographics must survive and the verdict must ride along in {@code validDsc} rather than erasing
     * them.
     */
    @Test
    void aadhaarWithInvalidDscIsReturnedNotCollapsedToNotReady() {
        when(digiLockerClient.getEAadhaar(anyString())).thenReturn(aadhaar(false));

        AadhaarResult r = adapter.digilockerAadhaar("REQ-DL-1");

        assertThat(r.validDsc()).isFalse();
        assertThat(r.fullName()).isEqualTo("EXAMPLE BORROWER");
        assertThat(r.dob()).isEqualTo("1994-06-11");
        assertThat(r.maskedAadhaar()).isEqualTo("XXXXXXXX1234");
        assertThat(r.dscSubject()).isEqualTo("Example Document Signer");
    }

    /**
     * The happy path, field by field. {@code AadhaarResult} reorders the vendor record (dob/gender move
     * ahead of the masked uid, dscSubject moves behind the address block) and the Aadhaar face photo is
     * carried in {@code profileImageBase64} even though Signzy hands back a persist URL — a positional
     * slip between two same-typed neighbours would compile, pass every other test here, and quietly
     * face-match the selfie against a PDF link.
     */
    @Test
    void aadhaarWithValidDscMapsEveryField() {
        when(digiLockerClient.getEAadhaar(anyString())).thenReturn(aadhaar(true));

        AadhaarResult r = adapter.digilockerAadhaar("REQ-DL-1");

        assertThat(r.txnId()).isEqualTo("REQ-DL-1");
        assertThat(r.fullName()).isEqualTo("EXAMPLE BORROWER");
        assertThat(r.dob()).isEqualTo("1994-06-11");
        assertThat(r.gender()).isEqualTo("M");
        assertThat(r.maskedAadhaar()).isEqualTo("XXXXXXXX1234");
        assertThat(r.fullAddress())
                .isEqualTo("12 Example Road, Example Colony, Example City, Example State - 110001");
        assertThat(r.state()).isEqualTo("Example State");
        assertThat(r.district()).isEqualTo("Example District");
        assertThat(r.city()).isEqualTo("Example City");
        assertThat(r.pincode()).isEqualTo("110001");
        assertThat(r.country()).isEqualTo("India");
        assertThat(r.addressLine()).isEqualTo("12 Example Road, Example Colony");
        assertThat(r.landmark()).isEqualTo("Near Example Park");
        assertThat(r.dscSubject()).isEqualTo("Example Document Signer");
        assertThat(r.profileImageBase64()).isEqualTo("https://example.invalid/photo.jpg");
        assertThat(r.pdfUrl()).isEqualTo("https://example.invalid/aadhaar.pdf");
        assertThat(r.jpegUrl()).isEqualTo("https://example.invalid/aadhaar.jpg");
        assertThat(r.xmlUrl()).isEqualTo("https://example.invalid/eaadhaar.xml");
        assertThat(r.validDsc()).isTrue();
    }

    /**
     * The classification is a whitelist of three known verdicts, not a rewrite of every failure. A 500
     * is Signzy broken on a call we have no verdict for: it must stay an ordinary
     * {@link VerificationException} so the router can still fall through, and must NOT become terminal
     * — {@code TerminalVerificationException} stops the chain, and an outage is precisely the case the
     * fall-through exists to rescue.
     */
    @Test
    void otherErrorsStillPropagate() {
        when(digiLockerClient.getEAadhaar(anyString()))
                .thenThrow(signzyFailure(500, "Internal Server Error"));

        assertThatThrownBy(() -> adapter.digilockerAadhaar("REQ-DL-1"))
                .isInstanceOf(VerificationException.class)
                .isNotInstanceOf(TerminalVerificationException.class)
                .hasMessageContaining("Signzy geteAadhaar failed");
    }
}
