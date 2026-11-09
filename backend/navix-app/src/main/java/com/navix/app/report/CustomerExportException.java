package com.navix.app.report;

import com.navix.common.exception.BusinessException;

/**
 * A rejected customer-export request that needs its own HTTP status (400 for a bad request, 503 for
 * {@code EXPORT_BUSY} so the client retries). {@link CustomerExportController} maps it; elsewhere it
 * is an ordinary {@link BusinessException}.
 */
public class CustomerExportException extends BusinessException {

    private final int status;

    public CustomerExportException(int status, String code, String message) {
        super(code, message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
