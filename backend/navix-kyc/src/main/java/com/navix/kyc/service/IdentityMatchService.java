package com.navix.kyc.service;

import org.springframework.stereotype.Service;

/**
 * Cross-matches identity attributes across sources:
 * PAN name/dob == Aadhaar name/dob, and derives aadhaar_linked from the
 * pan_comprehensive result.
 * STUB: matching rules to be implemented.
 * TODO: implement fuzzy name match, dob equality and linkage derivation.
 */
@Service
public class IdentityMatchService {

    /** Returns true when PAN and Aadhaar name/dob are considered a match. */
    public boolean matchesPanAadhaar(String panName, String panDob,
                                     String aadhaarName, String aadhaarDob) {
        // TODO: normalise and compare name (fuzzy) + dob (exact).
        throw new UnsupportedOperationException("IdentityMatchService.matchesPanAadhaar not implemented");
    }

    /** Derive whether Aadhaar is linked to PAN from a pan_comprehensive payload. */
    public boolean isAadhaarLinked(Object panComprehensiveResult) {
        // TODO: extract aadhaar_linked flag from the comprehensive PAN result.
        throw new UnsupportedOperationException("IdentityMatchService.isAadhaarLinked not implemented");
    }
}
