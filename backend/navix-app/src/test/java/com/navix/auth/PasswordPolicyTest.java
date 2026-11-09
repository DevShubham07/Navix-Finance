package com.navix.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.navix.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private static String pad(String head, int totalChars) {
        return head + "x".repeat(totalChars - head.length());
    }

    @Test
    void acceptsEightAndSeventyTwoBytes() {
        assertThatCode(() -> PasswordPolicy.validate("abcd123!")).doesNotThrowAnyException();
        assertThatCode(() -> PasswordPolicy.validate(pad("a1!", 72))).doesNotThrowAnyException();
    }

    @Test
    void refusesSevenCharsAnd73Bytes() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abc12!x")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(pad("a1!", 73))).isInstanceOf(BusinessException.class);
    }

    @Test
    void countsBytesNotChars() {
        String s = "é1!" + "é".repeat(35); // 39 chars, 74 bytes
        assertThatThrownBy(() -> PasswordPolicy.validate(s)).isInstanceOf(BusinessException.class);
    }

    @Test
    void stillRequiresLetterDigitAndSpecial() {
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdefg1")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("abcdefg!")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate("1234567!")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> PasswordPolicy.validate(null)).isInstanceOf(BusinessException.class);
    }
}
