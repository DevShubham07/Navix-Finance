package com.navix.kyc.service;

import com.navix.kyc.entity.DigiLockerSession;
import com.navix.kyc.repository.KycCaseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates DigiLocker sessions: init / poll / list / aadhaar-xml.
 * Calls navix-verification clients to talk to DigiLocker.
 * STUB: integration to be wired.
 * TODO: inject the navix-verification DigiLocker client and implement the
 *       init -> poll -> fetch-aadhaar-xml lifecycle. Never persist the full
 *       Aadhaar number, only a masked reference.
 */
@Service
@RequiredArgsConstructor
public class DigiLockerSessionService {

    private final KycCaseRepository kycCaseRepository;

    /** Initiate a new DigiLocker linking session for a borrower. */
    public DigiLockerSession init(Long borrowerId) {
        // TODO: call verification client, persist session in INITIATED state.
        throw new UnsupportedOperationException("DigiLockerSessionService.init not implemented");
    }

    /** Poll the current status of a session. */
    public DigiLockerSession poll(Long sessionId) {
        // TODO: query verification client and update local status.
        throw new UnsupportedOperationException("DigiLockerSessionService.poll not implemented");
    }

    /** List sessions for a borrower. */
    public List<DigiLockerSession> list(Long borrowerId) {
        // TODO: return sessions for the borrower.
        throw new UnsupportedOperationException("DigiLockerSessionService.list not implemented");
    }

    /** Fetch and parse the Aadhaar XML once linking is complete. */
    public Object fetchAadhaarXml(Long sessionId) {
        // TODO: retrieve Aadhaar XML, extract masked ref + demographics.
        throw new UnsupportedOperationException("DigiLockerSessionService.fetchAadhaarXml not implemented");
    }
}
