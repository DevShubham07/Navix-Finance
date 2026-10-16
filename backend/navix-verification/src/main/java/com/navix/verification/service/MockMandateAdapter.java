package com.navix.verification.service;

import com.navix.common.verification.MandatePort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Offline {@link MandatePort}: answers immediately, as an authorised mandate, without calling anyone. */
public class MockMandateAdapter implements MandatePort {

    @Override
    public MandateSession register(MandateRequest request) {
        return new MandateSession("ENAMOCK" + request.customerRefNumber(), "partial", "https://mock.digio.invalid/auth");
    }

    @Override
    public MandateResult fetch(String digioMandateId) {
        return new MandateResult(digioMandateId, "authentication_completed", "registered",
                "MOCK" + digioMandateId, "debit", null, null, Instant.now());
    }

    @Override
    public Optional<MandateResult> findByRef(String customerRefNumber) {
        return Optional.of(fetch("ENAMOCK" + customerRefNumber));
    }

    @Override
    public List<BankCapability> liveBanks() {
        return List.of(new BankCapability("MOCK", true, List.of("net_banking", "aadhaar", "debit")));
    }
}
