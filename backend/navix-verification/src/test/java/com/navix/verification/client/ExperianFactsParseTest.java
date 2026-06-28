package com.navix.verification.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Parses the bundled production sample report ({@code samplepan.json}) via the fixture path and
 * asserts every Category A/B/C field the credit brief depends on. The RestClient is never used in
 * fixture mode.
 */
class ExperianFactsParseTest {

    private ExperianClient fixtureClient() {
        return new ExperianClient(RestClient.create(), new ObjectMapper(), "classpath:samplepan.json");
    }

    @Test
    void parsesAllBriefFactsFromSampleReport() {
        ExperianResponse r = fixtureClient().pull("X", "Y", "Z", "ref");

        assertThat(r.creditScore()).isEqualTo(778);
        assertThat(r.noRecord()).isFalse();

        BureauReportFacts f = r.facts();
        assertThat(f).isNotNull();

        // A · Identity
        assertThat(f.name()).isEqualTo("KARTIK JINDAL");
        assertThat(f.pan()).isEqualTo("BXFPJ0767C");
        assertThat(f.dob()).isEqualTo("1985-07-10"); // 19850710 → YYYY-MM-DD
        assertThat(f.city()).isEqualTo("Mumbai");
        assertThat(f.pin()).isEqualTo("400001");

        // B · Credit health
        assertThat(f.creditScore()).isEqualTo(778);
        assertThat(f.totalAccounts()).isEqualTo(11);
        assertThat(f.activeAccounts()).isEqualTo(9);
        assertThat(f.closedAccounts()).isEqualTo(2);
        assertThat(f.defaults()).isEqualTo(0);

        // C · Exposure (rupees)
        assertThat(f.totalBalanceRupees()).isEqualTo(861232L);
        assertThat(f.securedBalanceRupees()).isEqualTo(712212L);
        assertThat(f.unsecuredBalanceRupees()).isEqualTo(149020L);
        assertThat(f.recentInquiries30d()).isEqualTo(5);

        assertThat(f.reportNumber()).isEqualTo("1782599074402");
    }
}
