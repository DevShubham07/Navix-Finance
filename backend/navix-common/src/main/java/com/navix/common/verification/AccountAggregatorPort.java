package com.navix.common.verification;

import java.util.Map;

/**
 * Port for the Digitap Account Aggregator (bank-data consent + statements). Keeps the vendor client
 * off the loan classpath, same rule as {@link EsignPort}. Failures that are a normal outcome
 * (consent not started yet, pull refused) come back as a {@code code}, never an exception; only a
 * transport/unexpected failure throws.
 */
public interface AccountAggregatorPort {

    /** A hosted consent link and the provider's request id. */
    record Link(String url, String requestId) {}

    /** {@code code}: ReportGenerated, TxnProcessing, TxnNotFound (not started yet), or a failure code. */
    record Status(String code, String txnId) {}

    /** Result of a periodic pull; when {@code ok} is false, {@code code}/{@code message} are the provider's. */
    record Pull(boolean ok, String code, String message, String txnId) {}

    /** Body is the provider's generateurl JSON, built by the caller. */
    Link generateUrl(Map<String, Object> body);

    /** {@code txnId} null = the main consent transaction; set = a periodic-pull transaction. */
    Status statusCheck(String requestId, String txnId);

    /** The type3 JSON report for a finished transaction, verbatim. */
    String retrieveReport(String txnId);

    Pull initiatePeriodicFetch(String mainTxnId, String templateId);
}
