package com.navix.kyc.service;

import com.navix.common.exception.ResourceNotFoundException;
import com.navix.kyc.entity.DigiLockerSession;
import com.navix.kyc.repository.DigiLockerSessionRepository;
import com.navix.verification.client.DigiLockerClient;
import com.navix.verification.dto.DigiLockerDtos.AadhaarXmlResponse;
import com.navix.verification.dto.DigiLockerDtos.InitializeResponse;
import com.navix.verification.dto.DigiLockerDtos.StatusResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestrates DigiLocker sessions: init / poll / list / aadhaar-xml. Calls the navix-verification
 * {@link DigiLockerClient} (demo mocks) to talk to DigiLocker and persists the local session row.
 *
 * <p>The full Aadhaar number is never persisted — only the masked reference surfaced by the
 * Aadhaar XML fetch.
 */
@Service
@RequiredArgsConstructor
public class DigiLockerSessionService {

    /** Default session validity sent to DigiLocker on initialize. */
    private static final int DEFAULT_EXPIRY_MINUTES = 10;

    private final DigiLockerSessionRepository digiLockerSessionRepository;
    private final DigiLockerClient digiLockerClient;

    /** Initiate a new DigiLocker linking session for a borrower. */
    @Transactional
    public DigiLockerSession init(Long borrowerId) {
        InitializeResponse response = digiLockerClient.initialize(
                "https://navix.demo/kyc/digilocker/callback", DEFAULT_EXPIRY_MINUTES, true);

        DigiLockerSession session = new DigiLockerSession();
        session.setBorrowerId(borrowerId);
        session.setClientId(response.clientId());
        session.setStatus(response.status());
        session.setAadhaarLinked(false);
        return digiLockerSessionRepository.save(session);
    }

    /** Poll the current status of a session and update the local row. */
    @Transactional
    public DigiLockerSession poll(Long sessionId) {
        DigiLockerSession session = get(sessionId);
        StatusResponse status = digiLockerClient.status(session.getClientId());
        session.setStatus(status.status());
        if (Boolean.TRUE.equals(status.completed())) {
            session.setAadhaarLinked(true);
        }
        return digiLockerSessionRepository.save(session);
    }

    /** List sessions for a borrower. */
    @Transactional(readOnly = true)
    public List<DigiLockerSession> list(Long borrowerId) {
        return digiLockerSessionRepository.findByBorrowerId(borrowerId);
    }

    /** Fetch and parse the Aadhaar XML once linking is complete (returns masked demographics). */
    @Transactional(readOnly = true)
    public AadhaarXmlResponse fetchAadhaarXml(Long sessionId) {
        DigiLockerSession session = get(sessionId);
        return digiLockerClient.aadhaarXml(session.getClientId());
    }

    private DigiLockerSession get(Long sessionId) {
        return digiLockerSessionRepository.findById(sessionId)
                .orElseThrow(() -> new ResourceNotFoundException("DigiLockerSession",
                        String.valueOf(sessionId)));
    }
}
