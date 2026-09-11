package com.navix.verification.service;

import com.navix.common.verification.VerificationPort;
import com.navix.verification.config.VerificationChainProperties;
import com.navix.verification.exception.CapabilityNotSupportedException;
import com.navix.verification.exception.VerificationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
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
 * <p>Net effect of the matrix: penny-drop + DigiLocker + the interactive liveness journey are Signzy-only;
 * the synchronous face-match ({@code faceLiveness}) and the UAN/EPFO employment lookup
 * ({@code verifyEmployment}) are Digitap-only; PAN, email and address try Signzy then fall back to
 * Digitap. Bureau is the one capability with a THREE-provider chain: Fintrix (primary) → Signzy
 * (unsupported — retired, always skipped) → Digitap (fallback); Signzy stays in the map only because
 * the chain is global across capabilities.
 */
@Component
@Primary
public class RoutingVerificationPort implements VerificationPort {

    private static final Logger log = LoggerFactory.getLogger(RoutingVerificationPort.class);

    /** Provider id → adapter, in the configured order. */
    private final Map<String, VerificationPort> providers = new LinkedHashMap<>();
    private final List<String> chain;
    /** Held directly for {@link #answerBureauChallenge}, which bypasses the chain — see its javadoc. */
    private final FintrixVerificationAdapter fintrix;
    /** See {@link #bureauAcceptable} — bureau-only, and off restores "a no-hit ends the chain". */
    private final boolean bureauNoHitFallThrough;

    public RoutingVerificationPort(FintrixVerificationAdapter fintrix,
                                   SignzyVerificationAdapter signzy,
                                   DigitapVerificationAdapter digitap,
                                   VerificationChainProperties props) {
        // An adapter missing from this map is silently ignored by the loop below — every adapter MUST
        // be listed here.
        this.fintrix = fintrix;
        this.bureauNoHitFallThrough = props.bureauNoHitFallThroughEnabled();
        Map<String, VerificationPort> all = Map.of("fintrix", fintrix, "signzy", signzy, "digitap", digitap);
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
            providers.put("fintrix", fintrix);
        }
    }

    /**
     * Try each provider in chain order for {@code capability}. Unsupported → skip; failed → fall through;
     * first success wins.
     */
    private <T> T route(String capability, Function<VerificationPort, T> call) {
        return route(capability, call, r -> true);
    }

    /**
     * As {@link #route(String, Function)}, but a result the {@code acceptable} predicate rejects is
     * remembered and the walk continues — used only by bureau, to keep looking past a no-hit.
     * Precedence of what comes back: acceptable result, then the FIRST unacceptable one, then a real
     * failure, then "unsupported".
     */
    private <T> T route(String capability, Function<VerificationPort, T> call, Predicate<T> acceptable) {
        VerificationException lastRealFailure = null;
        CapabilityNotSupportedException lastUnsupported = null;
        T firstUnacceptable = null;
        boolean haveUnacceptable = false;
        for (Map.Entry<String, VerificationPort> e : providers.entrySet()) {
            try {
                T result = call.apply(e.getValue());
                if (acceptable.test(result)) {
                    log.debug("verification[{}] served by {}", capability, e.getKey());
                    return result;
                }
                if (!haveUnacceptable) {
                    firstUnacceptable = result;
                    haveUnacceptable = true;
                }
                log.info("verification[{}] provider {} answered but found no record — falling through",
                        capability, e.getKey());
            } catch (CapabilityNotSupportedException unsupported) {
                lastUnsupported = unsupported; // provider doesn't offer this capability — try the next
            } catch (VerificationException failed) {
                log.warn("verification[{}] provider {} failed status={} endpoint={} code={} detail={} — falling through",
                        capability, e.getKey(), failed.httpStatus(), failed.endpoint(),
                        failed.providerCode(), failed.safeDetail());
                lastRealFailure = failed; // provider tried and failed — fall back to the next
            }
        }
        // An ANSWER beats the ABSENCE of one: if any provider said "no record", return that even when a
        // later provider then blew up. We already hold a valid bureau reply, and throwing it away to
        // rethrow would be strictly worse than the old behaviour, which terminated on that same reply.
        //
        // The FIRST such answer, not the last, and that matters: VerificationFailureService.discardedReport
        // is gated on provider==FINTRIX_CRIF with a non-blank txn id, so returning a trailing Fintrix
        // no-hit would flip the whole thin-file cohort into the "discarded report, offer a billable
        // re-run" bucket. Returning the chain head's answer also keeps bureauSource stable across re-runs.
        if (haveUnacceptable) {
            return firstUnacceptable;
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
    public BureauCheck pullBureau(String pan, String name, String mobile, String dob, String otp, String clientRef) {
        return route("bureau", p -> p.pullBureau(pan, name, mobile, dob, otp, clientRef), this::bureauAcceptable);
    }

    /**
     * A no-hit is the only bureau answer worth walking past: the bureau leading the chain simply has no
     * file on this borrower, and the other one is a genuinely different data source that may. Everything
     * else is terminal.
     *
     * <p>A {@code pendingChallenge} is deliberately ACCEPTABLE — the report exists and is merely gated
     * behind a KBA question, so falling through would burn a second billable pull and throw away a
     * usable answer. It already carries {@code noRecord=false}; the explicit check documents the intent
     * and survives a provider that ever sets both.
     */
    private boolean bureauAcceptable(BureauCheck b) {
        return !bureauNoHitFallThrough || b == null || b.pendingChallenge() != null || !b.noRecord();
    }

    /**
     * The one capability that deliberately does NOT walk the chain. A KBA {@code orderId} is minted by
     * — and only meaningful to — the provider that issued it, so falling through to Digitap with it
     * would be nonsense AND would burn a billable call. Fintrix is the only issuer, so go straight
     * there; its own feature-flag kill switch still applies.
     */
    @Override
    public BureauCheck answerBureauChallenge(String orderId, String reportId, String answer,
                                             String name, String mobile, String clientRef) {
        return fintrix.answerBureauChallenge(orderId, reportId, answer, name, mobile, clientRef);
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
    public EmploymentCheck verifyEmployment(String pan, String mobile, String dob, String employeeName,
                                            String employerName, String uan, String clientRef) {
        return route("employment",
                p -> p.verifyEmployment(pan, mobile, dob, employeeName, employerName, uan, clientRef));
    }

    @Override
    public LivenessSession livenessInit(String matchImageUrl, String clientRef) {
        return route("liveness-init", p -> p.livenessInit(matchImageUrl, clientRef));
    }

    @Override
    public LivenessResultCheck livenessResult(String token) {
        return route("liveness-result", p -> p.livenessResult(token));
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
