package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/**
 * {@code crif-combine-sample.json} is a REDACTED capture of a real Fintrix {@code /crif_combine}
 * response (see its {@code _comment}) — identity fields are synthetic, everything the parser depends
 * on (money grouping, {@code COMBINED-PAYMENT-HISTORY} formatting, the deliberately wrong
 * {@code PRIMARY-CURRENT-BALANCE}, the TRENDS series) is byte-faithful to the wire. Hand-built
 * fragments are used only for the isolated decoder/edge-case tests, same split as
 * {@code ExperianFactsParserTest}.
 */
class CrifHighmarkFactsParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode fixtureReport() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/crif-combine-sample.json")) {
            JsonNode root = mapper.readTree(in);
            return root.at("/canonical/data/credit_report");
        }
    }

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    @Test
    void fullFixtureParses() throws Exception {
        BureauReportFacts facts = CrifHighmarkFactsParser.parse(fixtureReport(), 799, "N", "P", "M");

        assertThat(facts).isNotNull();
        assertThat(facts.creditScore()).isEqualTo(799);
        assertThat(facts.reportNumber()).isEqualTo("CCR260822CR415935862");
        assertThat(facts.totalAccounts()).isEqualTo(7);
        assertThat(facts.activeAccounts()).isEqualTo(6);
        assertThat(facts.closedAccounts()).isEqualTo(1);
        assertThat(facts.defaults()).isEqualTo(0);
        assertThat(facts.totalBalanceRupees()).isEqualTo(819215L);
        assertThat(facts.securedBalanceRupees()).isEqualTo(702175L);
        assertThat(facts.unsecuredBalanceRupees()).isEqualTo(117040L);
        assertThat(facts.detail().tradelines()).hasSize(7);
        assertThat(facts.detail().enquiries()).hasSize(2);
        assertThat(facts.detail().scoreHistory()).isNotNull();
        assertThat(facts.detail().scoreHistory().points()).hasSize(12);
        assertThat(facts.detail().scoreHistory().points().get(0).score()).isEqualTo(799);
    }

    /**
     * The vendor's own {@code PRIMARY-CURRENT-BALANCE} summary field is {@code "0"} in the fixture
     * while the seven tradelines actually sum to ₹8,19,215 — the parser must ignore the summary and
     * sum {@code RESPONSES.RESPONSE[].LOAN-DETAILS.CURRENT-BAL} itself. This test exists to stop that
     * being "simplified" back to reading the summary field.
     */
    @Test
    void summaryBalanceIsIgnoredInFavourOfSummedTradelines() throws Exception {
        JsonNode report = fixtureReport();
        assertThat(report.path("ACCOUNTS-SUMMARY").path("PRIMARY-ACCOUNTS-SUMMARY")
                .path("PRIMARY-CURRENT-BALANCE").asText()).isEqualTo("0");

        BureauReportFacts facts = CrifHighmarkFactsParser.parse(report, 799, "N", "P", "M");
        assertThat(facts.totalBalanceRupees()).isEqualTo(819215L);
    }

    @Test
    void moneyParserHandlesIndianGroupingBlankNullZeroAndDecimals() {
        assertThat(MoneyParser.indianRupees("10,00,000")).isEqualTo(1_000_000L);
        assertThat(MoneyParser.indianRupees("1,12,685")).isEqualTo(112_685L);
        assertThat(MoneyParser.indianRupees("")).isNull();
        assertThat(MoneyParser.indianRupees(null)).isNull();
        assertThat(MoneyParser.indianRupees("0")).isEqualTo(0L);
        // Truncated, not rounded.
        assertThat(MoneyParser.indianRupees("21001.861309715707")).isEqualTo(21001L);
    }

    @Test
    void paymentHistoryDecoderReadsDpdAndTreatsXxxAsNotReported() {
        assertThat(PaymentHistory.worstDpd("Aug:2026,000/XXX|", Integer.MAX_VALUE)).isEqualTo(0);
        assertThat(PaymentHistory.worstDpd("Mar:2025,027/XXX|Feb:2025,000/XXX|", Integer.MAX_VALUE))
                .isEqualTo(27);
        assertThat(PaymentHistory.worstDpd("Aug:2026,XXX/STD|Jul:2026,XXX/STD|", Integer.MAX_VALUE))
                .isNull();
    }

    /** The HDFC 1006 tradeline in the fixture carries exactly one non-"XXX" month, DPD 27. */
    @Test
    void fixtureTradelineWithOneDelinquentMonthYieldsWorstDpd27() throws Exception {
        BureauReportFacts facts = CrifHighmarkFactsParser.parse(fixtureReport(), 799, "N", "P", "M");

        boolean found = facts.detail().tradelines().stream()
                .anyMatch(t -> "XXXXXXXXXXXXXXX1006".equals(t.accountNumberMasked())
                        && Integer.valueOf(27).equals(t.worstDpdMonths()));
        assertThat(found).isTrue();
    }

    @Test
    void thinFileReturnsNull() throws Exception {
        assertThat(CrifHighmarkFactsParser.parse(json("{}"), 799, "N", "P", "M")).isNull();
    }

    @Test
    void missingReportReturnsNull() {
        assertThat(CrifHighmarkFactsParser.parse(null, 799, "N", "P", "M")).isNull();
    }

    @Test
    void missingScoresSectionDoesNotThrow() throws Exception {
        String report = """
                {
                  "HEADER": {"REPORT-ID": "R1", "DATE-OF-ISSUE": "22-08-2026"},
                  "ACCOUNTS-SUMMARY": {
                    "PRIMARY-ACCOUNTS-SUMMARY": {"PRIMARY-NUMBER-OF-ACCOUNTS": "1"}
                  },
                  "RESPONSES": {
                    "RESPONSE": [{"LOAN-DETAILS": {"ACCT-NUMBER": "A1", "CURRENT-BAL": "100"}}]
                  }
                }
                """;
        BureauReportFacts facts = CrifHighmarkFactsParser.parse(json(report), 799, "N", "P", "M");

        assertThat(facts).isNotNull();
        assertThat(facts.creditScore()).isEqualTo(799);
        assertThat(facts.totalBalanceRupees()).isEqualTo(100L);
    }

    /** CRIF emits a bare object, not a one-element array, when exactly one RESPONSE exists. */
    @Test
    void singleBareObjectResponseStillParses() throws Exception {
        String report = """
                {
                  "HEADER": {"REPORT-ID": "R1"},
                  "RESPONSES": {
                    "RESPONSE": {"LOAN-DETAILS": {"ACCT-NUMBER": "SOLO", "CURRENT-BAL": "500"}}
                  }
                }
                """;
        BureauReportFacts facts = CrifHighmarkFactsParser.parse(json(report), 799, "N", "P", "M");

        assertThat(facts).isNotNull();
        assertThat(facts.detail().tradelines()).hasSize(1);
        assertThat(facts.detail().tradelines().get(0).accountNumberMasked()).isEqualTo("SOLO");
        assertThat(facts.totalBalanceRupees()).isEqualTo(500L);
    }
}
