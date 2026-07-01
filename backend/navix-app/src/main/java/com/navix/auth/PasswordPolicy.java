package com.navix.auth;

import com.navix.common.exception.BusinessException;

/**
 * The password complexity rule for any password a user sets (the optional set-password on signup and
 * the reset-link landing page): at least {@value #MIN_LENGTH} characters, including both a letter and
 * a digit. Throws {@code WEAK_PASSWORD} (422) otherwise.
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 10;

    private PasswordPolicy() {
    }

    public static void validate(String password) {
        boolean ok = password != null
                && password.length() >= MIN_LENGTH
                && password.chars().anyMatch(Character::isLetter)
                && password.chars().anyMatch(Character::isDigit);
        if (!ok) {
            throw new BusinessException("WEAK_PASSWORD",
                    "Password must be at least " + MIN_LENGTH + " characters and include both letters and digits.");
        }
    }
}
