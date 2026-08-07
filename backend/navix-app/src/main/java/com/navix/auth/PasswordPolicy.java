package com.navix.auth;

import com.navix.common.exception.BusinessException;

/**
 * The password complexity rule for any password a user sets — borrowers and staff alike
 * (revamp.md decision 23): {@value #MIN_LENGTH}–{@value #MAX_LENGTH} characters with a letter, a
 * digit and a special character. Throws {@code WEAK_PASSWORD} (422) otherwise.
 *
 * <p>Note the cap applies on <em>set</em>, not on login: an existing longer password (the seeded
 * {@code Admin@12345} is 11 characters) keeps working, but a reset must fit the new rule.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 6;
    public static final int MAX_LENGTH = 10;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        boolean ok = password != null
                && password.length() >= MIN_LENGTH
                && password.length() <= MAX_LENGTH
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit)
                && password.chars().anyMatch(c -> !Character.isLetterOrDigit(c) && !Character.isWhitespace(c));
        if (!ok) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be " + MIN_LENGTH + "–" + MAX_LENGTH
                            + " characters and include a letter, a digit and a special character.");
        }
    }
}
