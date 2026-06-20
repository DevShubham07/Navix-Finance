package com.navix.common.exception;

import lombok.Getter;

/**
 * Thrown when a domain/business rule is violated (e.g. loan amount exceeds the
 * 25% salary cap, repayment window invalid, maker-checker role conflict).
 *
 * TODO: define a stable set of error codes per business rule.
 */
@Getter
public class BusinessException extends RuntimeException {

    private final String code;

    public BusinessException(String message) {
        this("BUSINESS_RULE_VIOLATION", message);
    }

    public BusinessException(String code, String message) {
        super(message);
        this.code = code;
    }
}
