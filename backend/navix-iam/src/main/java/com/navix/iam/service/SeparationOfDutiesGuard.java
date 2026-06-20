package com.navix.iam.service;

import org.springframework.stereotype.Service;

/**
 * Enforces maker-checker separation of duties: the staff member who reviews a
 * loan must differ from the one who gives final approval, who must differ from
 * the one who releases disbursement.
 * TODO: implement actual identity comparison and throw a domain exception on
 * violation.
 */
@Service
public class SeparationOfDutiesGuard {

    /**
     * Verify reviewer, approver and releaser are three distinct staff members.
     *
     * @param reviewerId Credit Executive who reviewed the application
     * @param approverId Credit Head who gave final approval
     * @param releaserId Disbursement Head who released funds
     */
    public void assertDistinctActors(Long reviewerId, Long approverId, Long releaserId) {
        // TODO: enforce reviewerId != approverId != releaserId; raise on conflict.
        throw new UnsupportedOperationException("Not implemented yet");
    }
}
