package com.navix.app.provider;

import java.time.Instant;

/**
 * Row-level view of a provider call WITHOUT its request/response payloads — what the dashboard's
 * history table needs to render. The payloads come from the per-row detail endpoint.
 */
interface ProviderApiExecutionSummary {
    Long getId();
    String getOperation();
    String getProvider();
    String getStatus();
    Integer getHttpStatus();
    Long getDurationMs();
    String getSource();
    String getEndpoint();
    String getCheckType();
    Long getApplicationId();
    String getRequestId();
    String getErrorMessage();
    Instant getCreatedAt();
}
