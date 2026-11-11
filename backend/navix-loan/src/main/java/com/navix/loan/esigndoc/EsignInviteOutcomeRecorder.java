package com.navix.loan.esigndoc;

import com.navix.common.esign.EsignInviteOutcomeSink;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Writes the invite email outcome (reported from the async notification thread) onto the signer row. */
@Component
@RequiredArgsConstructor
public class EsignInviteOutcomeRecorder implements EsignInviteOutcomeSink {

    private final EsignDocumentSignerRepository signers;

    @Override
    @Transactional
    public void recordInviteOutcome(Long signerRowId, String status) {
        signers.findById(signerRowId).ifPresent(s -> {
            s.setEmailStatus(status);
            if ("SENT".equals(status)) {
                s.setEmailSentAt(Instant.now());
            }
        });
    }
}
