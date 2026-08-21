package com.navix.verification.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauCodes;
import com.navix.common.verification.BureauReportFacts;
import org.junit.jupiter.api.Test;

/**
 * Hand-built JSON fixtures only — a real Experian report is customer PII and is never checked in
 * (see {@code samplepan.json}'s caveat: it is a lab fixture, not what production shows). Each test
 * targets one specific hazard documented on {@link ExperianFactsParser}, {@link BureauTradeline} (via
 * {@code com.navix.common.verification}), or the sentinel/zero-padding/absent-vs-zero rules called out
 * in the parser's javadoc.
 */
class ExperianFactsParserTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) throws Exception {
        return mapper.readTree(raw);
    }

    /** Minimal report body with one tradeline, enough to clear the thin-file gate. */
    private String baseReport(String tradelineExtra) {
        return """
                {
                  "CAIS_Account": {
                    "CAIS_Summary": {
                      "Credit_Account": {
                        "CreditAccountTotal": "1",
                        "CreditAccountActive": "1",
                        "CreditAccountClosed": "0",
                        "CreditAccountDefault": "0"
                      },
                      "Total_Outstanding_Balance": {
                        "Outstanding_Balance_All": "10000",
                        "Outstanding_Balance_Secured": "0",
                        "Outstanding_Balance_UnSecured": "10000"
                      }
                    },
                    "CAIS_Account_DETAILS": %s
                  }
                }
                """.formatted(tradelineExtra);
    }

    /**
     * 900 is a real long-default reading, not a placeholder — 981 of the 30,820 production month
     * entries carry it, and 42 entries exceed it. Discarding it (as an earlier revision did) throws
     * away the worst delinquency on the file and reports the cleanest surviving month instead, which
     * understates risk on a credit report. This test exists to stop that being "simplified" back in.
     */
    @Test
    void longDefault900IsKeptAsTheWorstDpd() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "Account_Type": "5",
                  "CAIS_Account_History": [
                    {"Year":"2026","Month":"6","Days_Past_Due":"900"},
                    {"Year":"2026","Month":"5","Days_Past_Due":"30"}
                  ]
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts).isNotNull();
        assertThat(facts.detail().tradelines()).hasSize(1);
        assertThat(facts.detail().tradelines().get(0).worstDpdMonths()).isEqualTo(900);
        assertThat(facts.detail().delinquency().worstDpd12m()).isEqualTo(900);
        assertThat(facts.detail().delinquency().accountsEver30Plus()).isEqualTo(1);
    }

    /** Above the 900 convention is still a genuine reading (production tops out at 999). */
    @Test
    void dpdAbove900IsKept() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "CAIS_Account_History": [{"Year":"2026","Month":"6","Days_Past_Due":"999"}]
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().tradelines().get(0).worstDpdMonths()).isEqualTo(999);
    }

    @Test
    void emptyStringDaysPastDueIsSkipped() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "CAIS_Account_History": [
                    {"Year":"2026","Month":"6","Days_Past_Due":""},
                    {"Year":"2026","Month":"5","Days_Past_Due":"30"}
                  ]
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().tradelines().get(0).worstDpdMonths()).isEqualTo(30);
    }

    @Test
    void singleCharNPaymentHistoryMeansNoHistoryAndDoesNotCrash() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "Payment_History_Profile": "N"
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().tradelines().get(0).paymentHistory()).isEqualTo("N");
        assertThat(facts.detail().tradelines().get(0).worstDpdMonths()).isNull();
    }

    @Test
    void undocumentedCharLDoesNotCrashDecoding() {
        // 'L' is not in the vendor spec master — BureauCodes must return empty, never throw or guess.
        assertThat(BureauCodes.paymentHistoryBucket("L")).isEmpty();
    }

    @Test
    void negativeCurrentBalanceIsPreservedWithSign() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "Current_Balance": "-500"
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().tradelines().get(0).currentBalanceRupees()).isEqualTo(-500L);
    }

    @Test
    void caisAccountDetailsAsSingleObjectStillParses() throws Exception {
        String report = baseReport("""
                {
                  "Subscriber_Name": "SOLO BANK",
                  "Account_Type": "10"
                }
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().tradelines()).hasSize(1);
        assertThat(facts.detail().tradelines().get(0).lender()).isEqualTo("SOLO BANK");
        assertThat(facts.detail().tradelineCount()).isEqualTo(1);
    }

    @Test
    void missingCapsSectionYieldsEmptyEnquiriesAndNullVelocityNoException() throws Exception {
        String report = baseReport("""
                [{"Subscriber_Name": "TEST BANK"}]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().enquiries()).isEmpty();
        assertThat(facts.detail().enquiryVelocity().last7()).isNull();
        assertThat(facts.detail().enquiryVelocity().last30()).isNull();
        assertThat(facts.detail().enquiryVelocity().last90()).isNull();
        assertThat(facts.detail().enquiryVelocity().last180()).isNull();
    }

    @Test
    void absentAggregatesAreNullNotZero() throws Exception {
        String report = baseReport("""
                [{"Subscriber_Name": "TEST BANK"}]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        // A tradeline carrying none of the relevant fields leaves the DPD and arrears counts
        // UNKNOWN — inventing a 0 there would report a clean file we never actually verified.
        assertThat(facts.detail().delinquency().worstDpd12m()).isNull();
        assertThat(facts.detail().delinquency().accountsEver30Plus()).isNull();
        assertThat(facts.detail().delinquency().accountsCurrentlyPastDue()).isNull();
        assertThat(facts.detail().delinquency().oldestAccountOpenedOn()).isNull();
        // …but an absent written-off status IS the signal (5,910 of 6,417 production tradelines are
        // clean and carry no status), so with a tradeline present that count is a real zero.
        assertThat(facts.detail().delinquency().accountsWrittenOffOrSettled()).isZero();
    }

    @Test
    void zeroPaddedAccountTypeCodesMapCorrectly() {
        assertThat(BureauCodes.accountType("05")).contains("PERSONAL LOAN");
        assertThat(BureauCodes.accountType("01")).contains("AUTO LOAN");
        assertThat(BureauCodes.accountType("00")).contains("OTHERS");
    }

    @Test
    void thinFileStillReturnsNull() throws Exception {
        JsonNode report = json("""
                { "CAIS_Account": { "CAIS_Summary": {} } }
                """);
        assertThat(ExperianFactsParser.parse(report, 750, "N", "P", "M")).isNull();
    }

    @Test
    void nullReportReturnsNull() {
        assertThat(ExperianFactsParser.parse(null, 750, "N", "P", "M")).isNull();
    }

    @Test
    void enquiriesAndVelocityParseFromRealisticShape() throws Exception {
        String report = """
                {
                  "CAIS_Account": {
                    "CAIS_Summary": {
                      "Credit_Account": {"CreditAccountTotal": "1"},
                      "Total_Outstanding_Balance": {"Outstanding_Balance_All": "1000"}
                    },
                    "CAIS_Account_DETAILS": [{"Subscriber_Name": "BANK"}]
                  },
                  "CAPS": {
                    "CAPS_Application_Details": [
                      {
                        "Subscriber_Name": "LENDER X",
                        "Date_of_Request": "20250901",
                        "Enquiry_Reason": "7",
                        "Amount_Financed": "100000",
                        "Duration_Of_Agreement": "12"
                      }
                    ]
                  },
                  "TotalCAPS_Summary": {
                    "TotalCAPSLast7Days": "1",
                    "TotalCAPSLast30Days": "5",
                    "TotalCAPSLast90Days": "9",
                    "TotalCAPSLast180Days": "20"
                  }
                }
                """;
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        assertThat(facts.detail().enquiries()).hasSize(1);
        var enquiry = facts.detail().enquiries().get(0);
        assertThat(enquiry.requestedOn()).isEqualTo("2025-09-01");
        assertThat(enquiry.subscriber()).isEqualTo("LENDER X");
        assertThat(enquiry.reasonCode()).isEqualTo("7");
        assertThat(enquiry.amountFinancedRupees()).isEqualTo(100000L);
        assertThat(enquiry.durationMonths()).isEqualTo(12);

        assertThat(facts.detail().enquiryVelocity().last7()).isEqualTo(1);
        assertThat(facts.detail().enquiryVelocity().last30()).isEqualTo(5);
        assertThat(facts.detail().enquiryVelocity().last90()).isEqualTo(9);
        assertThat(facts.detail().enquiryVelocity().last180()).isEqualTo(20);
    }

    /**
     * A clean file must report a countable zero, not a dash. "0 accounts ever 30+ DPD" across
     * tradelines we actually read is a finding; "—" means we could not tell. A reviewer has to be
     * able to tell those apart, so the counters start at 0 the moment there is something to count.
     */
    @Test
    void cleanFileReportsZeroNotUnknown() throws Exception {
        String report = baseReport("""
                [{
                  "Subscriber_Name": "TEST BANK",
                  "Account_Type": "5",
                  "Account_Status": "11",
                  "Current_Balance": "1000",
                  "Amount_Past_Due": "0",
                  "CAIS_Account_History": [{"Year":"2026","Month":"6","Days_Past_Due":"0"}]
                }]
                """);
        BureauReportFacts facts = ExperianFactsParser.parse(json(report), 750, "N", "P", "M");

        var d = facts.detail().delinquency();
        assertThat(d.accountsEver30Plus()).isZero();
        assertThat(d.accountsCurrentlyPastDue()).isZero();
        assertThat(d.accountsWrittenOffOrSettled()).isZero();
    }

    /** …but with no tradelines to examine at all, the counts stay unknown rather than a false zero. */
    @Test
    void noTradelinesLeavesCountsUnknown() throws Exception {
        BureauReportFacts facts = ExperianFactsParser.parse(json(baseReport("[]")), 750, "N", "P", "M");

        var d = facts.detail().delinquency();
        assertThat(d.accountsEver30Plus()).isNull();
        assertThat(d.accountsCurrentlyPastDue()).isNull();
        assertThat(d.accountsWrittenOffOrSettled()).isNull();
    }
}
