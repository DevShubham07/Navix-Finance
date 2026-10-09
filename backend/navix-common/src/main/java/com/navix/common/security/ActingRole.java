package com.navix.common.security;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The "work as" sub-role a staff member may adopt (header {@link #HEADER}). It narrows LIST SCOPING
 * only; authorization always uses the real JWT role.
 */
public final class ActingRole {

    public static final String HEADER = "X-Acting-Role";

    private static final Map<String, Set<String>> ALLOWED = Map.of(
            "ADMIN", Set.of("CREDIT_HEAD", "CREDIT_EXECUTIVE", "DISBURSEMENT_HEAD", "ACCOUNTANT",
                    "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER"),
            "CREDIT_HEAD", Set.of("CREDIT_EXECUTIVE"),
            "COLLECTION_HEAD", Set.of("COLLECTION_EXECUTIVE"));

    private ActingRole() {}

    public static boolean allowed(String realRole, String acting) {
        return realRole != null && acting != null
                && ALLOWED.getOrDefault(realRole, Set.of()).contains(acting);
    }

    /** Trimmed upper-case header value when allowed and different from the real role, else null. */
    public static String normalize(String realRole, String header) {
        if (header == null) return null;
        String v = header.trim().toUpperCase(Locale.ROOT);
        return !v.equals(realRole) && allowed(realRole, v) ? v : null;
    }
}
