package com.navix.collections.service;

import com.navix.collections.entity.CollectionCase;
import com.navix.collections.entity.InteractionLog;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Core collections operations: opening cases for overdue loans, assigning
 * officers, and logging borrower interactions. Enforces invariants such as
 * "a PAID interaction outcome requires a proof reference".
 *
 * Business logic is stubbed for scaffolding.
 */
@Service
public class CollectionsService {

    /** Open (or fetch) a collection case for an overdue loan. TODO: implement. */
    public CollectionCase openCase(UUID loanId) {
        throw new UnsupportedOperationException("TODO: implement openCase");
    }

    /** Assign a collections officer to a case. TODO: implement. */
    public CollectionCase assignOfficer(UUID caseId, UUID officerId) {
        throw new UnsupportedOperationException("TODO: implement assignOfficer");
    }

    /** Log a borrower interaction. TODO: enforce PAID -> proofRef required. */
    public InteractionLog logInteraction(UUID caseId, InteractionLog interaction) {
        throw new UnsupportedOperationException("TODO: implement logInteraction");
    }
}
