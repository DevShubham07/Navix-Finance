package com.navix.verification.service;

import com.navix.common.verification.MandatePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Offline {@link MandatePort}: answers immediately, as an authorised mandate, without calling anyone.
 *
 * <p><b>Refuses to serve in prod.</b> {@code ProductionConfigGuard} only rejects {@code provider=mock}
 * while the {@code digio-mandate} flag is on, and flags are turned on by SQL with no redeploy — so a prod
 * instance booted on the mock would start serving real borrower journeys a mandate nobody filed the moment
 * the row lands. Failing every call makes that loud instead of silent; the fix is a redeploy with
 * {@code NAVIX_DIGIO_PROVIDER=digio} and the credentials.
 */
public class MockMandateAdapter implements MandatePort {

    private final boolean prod;

    public MockMandateAdapter(String env) {
        this.prod = "prod".equalsIgnoreCase(env);
    }

    private void refuseInProd() {
        if (prod) {
            throw new IllegalStateException("navix.digio.provider=mock in NAVIX_ENV=prod — the mock would "
                    + "report mandates as registered with nothing filed at NPCI. Set "
                    + "NAVIX_DIGIO_PROVIDER=digio (and the NAVIX_DIGIO_* credentials), or turn the "
                    + "digio-mandate flag off.");
        }
    }

    @Override
    public MandateSession register(MandateRequest request) {
        refuseInProd();
        return new MandateSession("ENAMOCK" + request.customerRefNumber(), "partial", "https://mock.digio.invalid/auth");
    }

    @Override
    public MandateResult fetch(String digioMandateId) {
        refuseInProd();
        return new MandateResult(digioMandateId, "authentication_completed", "registered",
                "MOCK" + digioMandateId, "debit", null, null, Instant.now());
    }

    @Override
    public Optional<MandateResult> findByRef(String customerRefNumber) {
        refuseInProd();
        return Optional.of(fetch("ENAMOCK" + customerRefNumber));
    }

    @Override
    public boolean cancelMandate(String digioMandateId, String umrn, String destIfsc, String reason) {
        refuseInProd();
        return true;
    }

    @Override
    public List<BankCapability> liveBanks() {
        refuseInProd();
        return List.of(new BankCapability("MOCK", true, List.of("net_banking", "aadhaar", "debit")));
    }

    @Override
    public DebitPlan presentDebit(DebitRequest request) {
        refuseInProd();
        return new DebitPlan("NSPMOCK" + request.uniqueKey(), "active", "NPTMOCK" + request.uniqueKey(),
                request.settlementDate());
    }

    @Override
    public DebitOutcome debitStatus(String txnId) {
        refuseInProd();
        // PAYMENT_SUCCESS, not "SUCCESS": applyOutcome maps Digio's vocabulary, and a mock that
        // answers anything else leaves a local/demo debit stalled as an unrecognised status.
        return new DebitOutcome(txnId, "PAYMENT_SUCCESS", "MOCKUTR" + txnId, null, null, 0L);
    }

    @Override
    public boolean cancelDebit(String txnId) {
        refuseInProd();
        return true;
    }

    @Override
    public Optional<DebitPlan> findDebitByRef(String uniqueKey) {
        refuseInProd();
        return Optional.empty();
    }
}
