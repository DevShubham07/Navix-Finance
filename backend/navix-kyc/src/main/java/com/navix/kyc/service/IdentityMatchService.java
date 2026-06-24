package com.navix.kyc.service;

import com.navix.verification.dto.FintrixDtos.PanResponse;
import org.springframework.stereotype.Service;

/**
 * Cross-matches identity attributes across sources: PAN name/dob == Aadhaar name/dob, and
 * derives aadhaar_linked from the pan_comprehensive result.
 *
 * <p>Pure helper (no I/O): name comparison is whitespace/case-insensitive, dob comparison is
 * exact. Used by the KYC orchestration to corroborate identity.
 */
@Service
public class IdentityMatchService {

    /** Returns true when PAN and Aadhaar name/dob are considered a match. */
    public boolean matchesPanAadhaar(String panName, String panDob,
                                     String aadhaarName, String aadhaarDob) {
        return namesMatch(panName, aadhaarName) && exact(panDob, aadhaarDob);
    }

    /** Derive whether Aadhaar is linked to PAN from a pan_comprehensive payload. */
    public boolean isAadhaarLinked(Object panComprehensiveResult) {
        if (panComprehensiveResult instanceof PanResponse pan) {
            return Boolean.TRUE.equals(pan.aadhaarLinked());
        }
        return false;
    }

    private static boolean namesMatch(String a, String b) {
        return normalise(a) != null && normalise(a).equals(normalise(b));
    }

    private static boolean exact(String a, String b) {
        return a != null && a.equals(b);
    }

    private static String normalise(String s) {
        if (s == null) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ").toUpperCase();
    }
}
