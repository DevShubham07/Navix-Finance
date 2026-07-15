package com.navix.verification.service;

import com.navix.common.verification.VerificationPort;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

/**
 * The sole {@link Primary} {@link VerificationPort} — the provider ROUTER consumed by
 * {@code ApplicationVerificationService}. For each capability it walks the configured chain
 * (default {@code signzy, digitap}) and:
 * <ul>
 *   <li>skips a provider that throws {@link CapabilityNotSupportedException} ("provider can't do this"),</li>
 *   <li>falls through to the next on a {@link VerificationException} ("provider tried and failed"),</li>
 *   <li>returns the first success (logging which provider served — never any PII).</li>
 * </ul>
 * If every provider in the chain is unsupported or fails, the last error is rethrown.
 *
 * <p>Net effect of the matrix: penny-drop + DigiLocker are Signzy-only; email + address are Digitap-only;
 * PAN, bureau and face-liveness try Signzy then fall back to Digitap.
 */
@Component
@Primary
public class RoutingVerificationPort implements VerificationPort {

    private static final Logger log = LoggerFactory.getLogger(RoutingVerificationPort.class);

    /** Provider id → adapter, in the configured order. */
    private final Map<String, VerificationPort> providers = new LinkedHashMap<>();
    private final List<String> chain;

    public RoutingVerificationPort(SignzyVerificationAdapter signzy,
                                   DigitapVerificationAdapter digitap,
                                   VerificationChainProperties props) {
        Map<String, VerificationPort> all = Map.of("signzy", signzy, "digitap", digitap);
        this.chain = props.effectiveChain();
        for (String id : chain) {
            VerificationPort p = all.get(id.trim().toLowerCase());
            if (p != null) {
                providers.put(id.trim().toLowerCase(), p);
            } else {
                log.warn("Unknown verification provider '{}' in navix.verification.chain — ignored", id);
            }
        }
        if (providers.isEmpty()) {
            // Never leave the router empty; fall back to the documented default order.
            providers.put("signzy", signzy);
            providers.put("digitap", digitap);
        }
    }

    /**
     * Try each provider in chain order for {@code capability}. Unsupported → skip; failed → fall through;
     * first success wins.
     */
    private <T> T route(String capability, Function<VerificationPort, T> call) {
        VerificationException lastRealFailure = null;
        CapabilityNotSupportedException lastUnsupported = null;
        for (Map.Entry<String, VerificationPort> e : providers.entrySet()) {
            try {
                T result = call.apply(e.getValue());
                log.debug("verification[{}] served by {}", capability, e.getKey());
                return result;
            } catch (CapabilityNotSupportedException unsupported) {
                lastUnsupported = unsupported; // provider doesn't offer this capability — try the next
            } catch (VerificationException failed) {
                log.warn("verification[{}] provider {} failed — falling through", capability, e.getKey());
                lastRealFailure = failed; // provider tried and failed — fall back to the next
            }
        }
        // Prefer a real upstream failure over a "capability unsupported" skip: it's the actionable error.
        // Only when every provider merely skipped do we surface the unsupported signal.
        if (lastRealFailure != null) {
            throw lastRealFailure;
        }
        if (lastUnsupported != null) {
            throw lastUnsupported;
        }
        throw new VerificationException("No provider in the chain could serve " + capability);
    }

    @Override
    public PanCheck verifyPan(String pan, String clientRef) {
        return route("pan", p -> p.verifyPan(pan, clientRef));
    }

    @Override
    public EmailCheck verifyEmail(String email, String individualName, String establishmentName, String clientRef) {
        return route("email", p -> p.verifyEmail(email, individualName, establishmentName, clientRef));
    }

    @Override
    public AddressCheck verifyAddress(double latitude, double longitude, String clientRef) {
        return route("address", p -> p.verifyAddress(latitude, longitude, clientRef));
    }

    @Override
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String clientRef) {
        return route("bureau", p -> p.pullBureau(pan, name, mobile, dob, clientRef));
    }

    @Override
    public PennyDropCheck pennyDrop(String accountNumber, String ifsc, String clientRef) {
        return route("penny-drop", p -> p.pennyDrop(accountNumber, ifsc, clientRef));
    }

    @Override
    public FaceLivenessCheck faceLiveness(String imageUrl, String referenceImageUrl, String clientRef) {
        return route("face-match", p -> p.faceLiveness(imageUrl, referenceImageUrl, clientRef));
    }

    @Override
    public DigiLockerSession digilockerInit(String redirectUrl, int expiryMinutes, boolean signupFlow) {
        return route("digilocker-init", p -> p.digilockerInit(redirectUrl, expiryMinutes, signupFlow));
    }

    @Override
    public DigiLockerStatus digilockerStatus(String clientId) {
        return route("digilocker-status", p -> p.digilockerStatus(clientId));
    }

    @Override
    public List<DigiLockerDoc> digilockerList(String clientId) {
        return route("digilocker-list", p -> p.digilockerList(clientId));
    }

    @Override
    public DigiLockerDownload digilockerDownload(String clientId, String fileId) {
        return route("digilocker-download", p -> p.digilockerDownload(clientId, fileId));
    }

    @Override
    public AadhaarResult digilockerAadhaar(String clientId) {
        return route("digilocker-aadhaar", p -> p.digilockerAadhaar(clientId));
    }
}
