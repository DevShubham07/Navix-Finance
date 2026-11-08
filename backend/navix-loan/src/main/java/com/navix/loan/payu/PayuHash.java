package com.navix.loan.payu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;

/** PayU's three SHA-512 hash formulas (docs/payu/README.md sections 5 and 6). Pure; the salt is only ever an input. */
public final class PayuHash {

    private PayuHash() {
    }

    /** {@code key|txnid|amount|productinfo|firstname|email|udf1..udf5||||||salt[|additionalCharges]}. */
    public static String request(String key, String txnid, String amount, String productinfo, String firstname,
                                 String email, String udf1, String udf2, String udf3, String udf4, String udf5,
                                 String salt, String additionalChargesOrNull) {
        String s = String.join("|", n(key), n(txnid), n(amount), n(productinfo), n(firstname), n(email),
                n(udf1), n(udf2), n(udf3), n(udf4), n(udf5), "", "", "", "", "", n(salt));
        if (additionalChargesOrNull != null && !additionalChargesOrNull.isBlank()) {
            s = s + "|" + additionalChargesOrNull;
        }
        return sha512(s);
    }

    /**
     * {@code [additionalCharges|]salt|status||||||udf5..udf1|email|firstname|productinfo|amount|txnid|key}.
     * The additionalCharges prefix applies only when PayU sent that field non-blank (sandbox-verified).
     */
    public static String reverse(Map<String, String> f, String salt) {
        StringBuilder s = new StringBuilder();
        String ac = f.get("additionalCharges");
        if (ac != null && !ac.isBlank()) {
            s.append(ac).append('|');
        }
        s.append(n(salt)).append('|').append(n(f.get("status"))).append("||||||")
                .append(n(f.get("udf5"))).append('|').append(n(f.get("udf4"))).append('|')
                .append(n(f.get("udf3"))).append('|').append(n(f.get("udf2"))).append('|')
                .append(n(f.get("udf1"))).append('|').append(n(f.get("email"))).append('|')
                .append(n(f.get("firstname"))).append('|').append(n(f.get("productinfo"))).append('|')
                .append(n(f.get("amount"))).append('|').append(n(f.get("txnid"))).append('|')
                .append(n(f.get("key")));
        return sha512(s.toString());
    }

    /** {@code key|command|var1|salt}, for {@code verify_payment}. */
    public static String command(String key, String command, String var1, String salt) {
        return sha512(n(key) + "|" + n(command) + "|" + n(var1) + "|" + n(salt));
    }

    /** Constant-time comparison of two hex hashes (case-insensitive on the received side). */
    public static boolean matches(String expected, String received) {
        if (expected == null || received == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                received.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }

    private static String sha512(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-512").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(d.length * 2);
            for (byte b : d) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-512 is mandatory on every JVM
        }
    }
}
