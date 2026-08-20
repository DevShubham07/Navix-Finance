package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * The fallback attribution path: when the request-scoped context is empty (a provider callback on its
 * own thread), the application is recovered from the client ref we embed in every payload.
 */
class ProviderClientRefTest {

    @Test
    void recoversApplicationIdAndCheckTypeFromAPayload() {
        String payload = "{\"client_ref_num\":\"navix-116-BUREAU\",\"pan\":\"ABCDE1234F\"}";
        assertThat(ProviderClientRef.applicationId(payload)).isEqualTo(116L);
        assertThat(ProviderClientRef.checkType(payload)).isEqualTo("BUREAU");
    }

    @Test
    void handlesAnUnderscoredCheckType() {
        assertThat(ProviderClientRef.checkType("{\"ref\":\"navix-9-PENNY_DROP\"}")).isEqualTo("PENNY_DROP");
    }

    @Test
    void returnsNullWhenThereIsNoClientRef() {
        assertThat(ProviderClientRef.applicationId("{\"ref\":\"admin-workbench\"}")).isNull();
        assertThat(ProviderClientRef.checkType(null)).isNull();
        assertThat(ProviderClientRef.applicationId("")).isNull();
    }
}
