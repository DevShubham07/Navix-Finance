package com.navix.sms;

/** Raised when the SMS gateway rejects a send (non-success ErrorCode / transport failure). */
public class SmsException extends RuntimeException {
    public SmsException(String message) {
        super(message);
    }

    public SmsException(String message, Throwable cause) {
        super(message, cause);
    }
}
