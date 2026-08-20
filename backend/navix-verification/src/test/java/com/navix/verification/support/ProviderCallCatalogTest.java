package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderCallCatalogTest {

    @Test
    void resolvesEveryKnownProviderEndpoint() {
        assertThat(ProviderCallCatalog.operationFor("/credit_analytics/request")).isEqualTo("BUREAU");
        assertThat(ProviderCallCatalog.providerFor("/credit_analytics/request")).isEqualTo("DIGITAP");
        // The two Signzy bureaux stay distinguishable — the router tries Experian, then CRIF.
        assertThat(ProviderCallCatalog.providerFor("/api/v3/bureau/experian-lite")).isEqualTo("SIGNZY_EXPERIAN");
        assertThat(ProviderCallCatalog.providerFor("/api/v3/bureau/crif")).isEqualTo("SIGNZY_CRIF");
        assertThat(ProviderCallCatalog.operationFor("/api/v3/bankaccountverification/pennydrop-v1"))
                .isEqualTo("PENNY_DROP");
        assertThat(ProviderCallCatalog.operationFor("/api/v3/contract/pullData")).isEqualTo("ESIGN");
        assertThat(ProviderCallCatalog.operationFor("/api/v3/digilocker-v2/geteAadhaar")).isEqualTo("DIGILOCKER");
    }

    @Test
    void ignoresAQueryStringWhenResolving() {
        assertThat(ProviderCallCatalog.operationFor("/fmfl/v2/face-match?retry=1")).isEqualTo("FACE_MATCH");
    }

    @Test
    void anUnmappedEndpointStillGetsAuditedRatherThanDropped() {
        assertThat(ProviderCallCatalog.operationFor("/api/v3/something/new")).isEqualTo("OTHER");
        assertThat(ProviderCallCatalog.providerFor("/api/v3/something/new")).isEqualTo("SIGNZY");
        assertThat(ProviderCallCatalog.providerFor("/some/digitap/path")).isEqualTo("DIGITAP");
    }
}
