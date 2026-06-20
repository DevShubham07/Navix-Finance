package com.navix.loan.domain;

/**
 * Types of loan documents generated/stored during the lifecycle.
 *
 * <ul>
 *   <li>{@link #AGREEMENT} - the signed loan agreement.</li>
 *   <li>{@link #SANCTION} - the sanction letter.</li>
 *   <li>{@link #KFS} - the Key Facts Statement.</li>
 * </ul>
 */
public enum LoanDocumentType {
    AGREEMENT,
    SANCTION,
    KFS
}
