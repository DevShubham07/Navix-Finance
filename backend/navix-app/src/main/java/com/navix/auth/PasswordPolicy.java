package com.navix.auth;

import com.navix.common.exception.BusinessException;

import java.nio.charset.StandardCharsets;

/**
 * The password rule for any password a user sets — borrowers and staff alike (revamp.md decision
 * 23): at least {@value #MIN_LENGTH} characters and at most {@value #MAX_BYTES} UTF-8 bytes (BCrypt's
 * input limit; it silently ignores anything past 72 bytes), with a letter, a digit and a special
 * character. Throws {@code WEAK_PASSWORD} (422) otherwise.
 *
 * <p>Note the policy applies on <em>set</em>, not on login: an existing password that predates the
 * rule keeps working, but a new one must fit it.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 8;
    public static final int MAX_BYTES = 72;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        boolean ok = password != null
                && password.length() >= MIN_LENGTH
                && password.getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!ok) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be at least " + MIN_LENGTH + " characters and at most " + MAX_BYTES
                            + " bytes, and include a letter, a digit and a special character.");
        }
    }
}
