package com.navix.verification.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Loads a redacted real vendor response body from {@code src/test/resources/vendor-envelopes}.
 *
 * <p>Tests drive off the actual production shapes rather than JSON invented at the keyboard. Several
 * of the bugs this suite now pins existed because the real shape was never looked at — Fintrix's KBA
 * follow-up answers with {@code error_message} as an OBJECT, which no vendor document mentions and
 * which our parser assumed was a string for months.
 *
 * <p>See the directory's README for what was redacted.
 */
public final class VendorEnvelopes {

    private VendorEnvelopes() {
    }

    public static String load(String fileName) {
        String path = "/vendor-envelopes/" + fileName;
        try (InputStream in = VendorEnvelopes.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new AssertionError("Missing vendor envelope fixture: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException unreadable) {
            throw new AssertionError("Could not read vendor envelope fixture: " + path, unreadable);
        }
    }
}
