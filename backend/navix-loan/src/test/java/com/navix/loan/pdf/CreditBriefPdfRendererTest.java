package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.verification.BureauDelinquency;
import com.navix.common.verification.BureauDetail;
import com.navix.common.verification.BureauEnquiry;
import com.navix.common.verification.BureauEnquiryVelocity;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauScoreHistory;
import com.navix.common.verification.BureauTradeline;
import com.navix.loan.service.CreditRatingCalculator;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Smoke test: the renderer emits a valid (non-empty, %PDF-headed) document for a full + a sparse facts set. */
class CreditBriefPdfRendererTest {

    private final CreditBriefPdfRenderer renderer = new CreditBriefPdfRenderer();
    private final CreditRatingCalculator calc = new CreditRatingCalculator();

    @Test
    void rendersValidPdfForFullReport() {
        BureauReportFacts f = new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0, 861232L, 712212L, 149020L, 5, "1782599074402");

        byte[] pdf = renderer.render(123L, 45L, "EXPERIAN", f, calc.rate(f), LocalDate.of(2026, 6, 28));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rendersWhenFactsAreSparse() {
        // Missing/blank fields must not throw — they render as "—".
        BureauReportFacts f = new BureauReportFacts(
                null, null, null, null, null, null, 705, null, null, null, null,
                null, null, null, null, null);

        byte[] pdf = renderer.render(1L, null, null, f, calc.rate(f), LocalDate.of(2026, 6, 28));

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void completeProviderResponseIsRenderedWithoutDroppingNestedOrArrayFields() throws Exception {
        BureauReportFacts f = new BureauReportFacts(
                "TEST BORROWER", "ABCDE1234F", "9000000000", "1990-01-01", "Testville", "100001",
                778, 11, 9, 2, 0, 805314L, 717556L, 87758L, 0, "TEST-REPORT-1");
        String raw = """
                {"http_response_code":200,"request_id":"REQ-FULL","result":{"result_json":{
                  "INProfileResponse":{
                    "Header":{"ReportDate":"20260809"},
                    "Current_Application":{"Current_Application_Details":{"Current_Other_Details":{"Income":"85000"}}},
                    "CAIS_Account":{"CAIS_Account_DETAILS":[{
                      "Subscriber_Name":"TEST BANK","Payment_History_Profile":"000000",
                      "CAIS_Account_History":[{"Year":"2026","Days_Past_Due":"0"}]
                    }]},
                    "CAPS":{"CAPS_Application_Details":[{"Amount_Financed":"10000"}]},
                    "SCORE":{"BureauScore":"778","BureauScoreConfidLevel":"H"}
                  }
                }}}
                """;

        byte[] pdf = renderer.render(123L, 45L, "DIGITAP_EXPERIAN", f, calc.rate(f),
                LocalDate.of(2026, 8, 9), raw);

        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(new PdfTextExtractor(reader).getTextFromPage(page));
        }
        reader.close();
        String normalized = text.toString().replaceAll("\\s+", " ");
        assertThat(normalized).contains(
                "Complete Provider Response",
                "Subscriber Name", "TEST BANK",
                "Payment History Profile", "000000",
                "Days Past Due",
                "Amount Financed", "10000",
                "Bureau Score Confid Level", "H");
    }

    /**
     * `facts.detail()` is null on any brief generated before tradeline parsing shipped (no backfill
     * ran) — the renderer must skip the structured sections entirely rather than print an empty
     * heading, so an older brief renders byte-for-byte the same shape it always did.
     */
    @Test
    void nullDetailOmitsStructuredSectionsEntirely() throws Exception {
        BureauReportFacts f = new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0, 861232L, 712212L, 149020L, 5, "1782599074402");
        assertThat(f.detail()).isNull();

        byte[] pdf = renderer.render(123L, 45L, "EXPERIAN", f, calc.rate(f), LocalDate.of(2026, 6, 28));

        String normalized = extractText(pdf);
        assertThat(normalized).doesNotContain("Delinquency History", "Enquiry Velocity", "Tradelines", "Enquiries");
    }

    /**
     * A brief with populated `detail` gets the four structured sections, driven entirely by
     * {@code BureauCodes} labels + the null-vs-zero / 900+ / bounding rules — mirrors the staff UI's
     * credit/tradeline-table.tsx field-for-field.
     */
    @Test
    void populatedDetailRendersStructuredSectionsWithCorrectLabelsAndBounding() throws Exception {
        BureauTradeline delinquent = new BureauTradeline(
                "ABC BANK", "XXXX1234", "5" /* Personal Loan */, "I", "97" /* Delinquent */,
                "2020-01-01", null, 50_000L, 12_000L, null, "111111111111", null, null, 950);
        // Settled with nothing past due — must be filtered out of the printed (default-visible) slice.
        BureauTradeline settled = new BureauTradeline(
                "OLD SETTLED BANK", "XXXX5678", "10", "R", "32" /* Settled */,
                "2015-01-01", "2019-01-01", 0L, 0L, null, "N", "Settled in full", 0L, null);
        // Unmapped type/status codes — must fall back to "Type <code>"/"Status <code>", never a guess.
        BureauTradeline unmappedCodes = new BureauTradeline(
                "NEW LENDER", "XXXX9999", "999", "L", "5",
                "2024-06-01", null, 1_000L, 0L, null, null, null, null, null);
        BureauDetail detail = new BureauDetail(
                List.of(delinquent, settled, unmappedCodes),
                5, // true report total exceeds what was parsed/returned — caption must say so
                List.of(
                        new BureauEnquiry("2026-01-15", "XYZ NBFC", "13" /* Personal Loan */, 20_000L, 12),
                        new BureauEnquiry("2025-06-01", "UNKNOWN LENDER", "77" /* unmapped */, null, null)),
                new BureauDelinquency(950, null, 10, null, 2, 0, null),
                new BureauEnquiryVelocity(1, null, 5, null));
        BureauReportFacts f = new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0, 861232L, 712212L, 149020L, 5, "1782599074402", detail);

        byte[] pdf = renderer.render(123L, 45L, "EXPERIAN", f, calc.rate(f), LocalDate.of(2026, 6, 28));

        String normalized = extractText(pdf);
        assertThat(normalized).contains(
                "Delinquency History", "Enquiry Velocity", "Tradelines", "Enquiries",
                // 900+ rule: a worst-DPD reading >= 900 is a genuine long-default signal, not hidden.
                "900+ days",
                // BureauCodes labels for mapped codes.
                "ABC BANK", "PERSONAL LOAN", "DELINQUENT",
                "XYZ NBFC",
                // Unmapped-code fallbacks — never a guessed label.
                "Type 999", "Status 5", "Reason 77",
                // Bounding: the settled/no-past-due tradeline is excluded, and the caption says so.
                "Showing 2 of 5");
        assertThat(normalized).doesNotContain("OLD SETTLED BANK");
    }

    /**
     * A {@code detail} with no scoreHistory (Experian, or a CRIF backfill run before this field
     * existed) must render exactly like before — no "Score Trend" heading, no "Top Exposures" strip
     * (that one rides on tradelines, not scoreHistory, but is only ever added alongside the tradeline
     * table so is covered here too since this detail's tradelines all resolve to a single positive
     * balance).
     */
    @Test
    void nullScoreHistoryOmitsScoreTrendSection() throws Exception {
        BureauTradeline delinquent = new BureauTradeline(
                "ABC BANK", "XXXX1234", "5", "I", "97",
                "2020-01-01", null, 50_000L, 12_000L, null, "111111111111", null, null, 950);
        BureauDetail detail = new BureauDetail(
                List.of(delinquent), 1, List.of(),
                new BureauDelinquency(950, null, 10, null, 2, 0, null),
                new BureauEnquiryVelocity(1, null, 5, null));
        assertThat(detail.scoreHistory()).isNull();
        BureauReportFacts f = new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                778, 11, 9, 2, 0, 861232L, 712212L, 149020L, 5, "1782599074402", detail);

        byte[] pdf = renderer.render(123L, 45L, "EXPERIAN", f, calc.rate(f), LocalDate.of(2026, 6, 28));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        assertThat(extractText(pdf)).doesNotContain("Score Trend");
    }

    /**
     * A populated {@code scoreHistory} (CRIF-only) draws the sparkline + credit-age stats, and the
     * tradelines behind it drive the "Top Exposures" strip — largest live balances first, closed/settled
     * excluded.
     */
    @Test
    void populatedScoreHistoryRendersSparklineAndTopExposures() throws Exception {
        BureauTradeline live1 = new BureauTradeline(
                "SBI", "XXXX0001", "2", "S", "11",
                "2018-01-01", null, 702_175L, 0L, null, "000000000000", null, null, 0);
        BureauTradeline live2 = new BureauTradeline(
                "HDFC BANK", "XXXX0002", "10", "R", "11",
                "2019-01-01", null, 112_685L, 0L, null, "000000000000", null, null, 0);
        BureauTradeline closed = new BureauTradeline(
                "OLD CLOSED BANK", "XXXX0003", "10", "R", "13",
                "2010-01-01", "2015-01-01", 999_999L, 0L, null, "N", null, null, null);
        BureauScoreHistory scoreHistory = new BureauScoreHistory(
                List.of(
                        new BureauScoreHistory.Point("2026-06-30", 799),
                        new BureauScoreHistory.Point("2026-03-31", 780),
                        new BureauScoreHistory.Point("2025-12-31", 760)),
                87, 42, 1, 0, 3);
        BureauDetail detail = new BureauDetail(
                List.of(live1, live2, closed), 3, List.of(),
                new BureauDelinquency(0, null, 0, 0, 0, 0, "2018-01-01"),
                new BureauEnquiryVelocity(0, 1, 3, 5),
                scoreHistory);
        BureauReportFacts f = new BureauReportFacts(
                "KARTIK JINDAL", "BXFPJ0767C", "95880784XX", "1985-07-10", "Mumbai", "400001",
                799, 3, 2, 1, 0, 819_215L, 702_175L, 117_040L, 0, "CCR260822CR415935862", detail);

        byte[] pdf = renderer.render(123L, 45L, "FINTRIX_CRIF", f, calc.rate(f), LocalDate.of(2026, 8, 22));

        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
        String normalized = extractText(pdf);
        assertThat(normalized).contains(
                "Score Trend", "Credit history length", "Average account age",
                "Top Exposures", "SBI", "HDFC BANK");
        // Highest live balance (SBI) leads, and the closed account never counts as an exposure.
        assertThat(normalized.indexOf("SBI")).isLessThan(normalized.indexOf("HDFC BANK"));
        assertThat(normalized).doesNotContain("OLD CLOSED BANK");
    }

    /**
     * {@code providerReportTable} (the raw-field appendix) is skipped for a Fintrix CRIF source — it
     * would blow {@code MAX_PROVIDER_FIELDS}'s cap and run to many pages (see that constant's javadoc)
     * — but stays for the older Experian/Digitap-shaped sources.
     */
    @Test
    void rawAppendixIsAbsentForFintrixCrifButPresentForExperian() throws Exception {
        BureauReportFacts f = new BureauReportFacts(
                "TEST BORROWER", "ABCDE1234F", "9000000000", "1990-01-01", "Testville", "100001",
                778, 11, 9, 2, 0, 805314L, 717556L, 87758L, 0, "TEST-REPORT-1");
        String raw = "{\"HEADER\":{\"REPORT-ID\":\"R1\"}}";

        byte[] crifPdf = renderer.render(1L, 2L, "FINTRIX_CRIF", f, calc.rate(f),
                LocalDate.of(2026, 8, 22), raw);
        assertThat(extractText(crifPdf)).doesNotContain("Complete Provider Response");

        byte[] experianPdf = renderer.render(1L, 2L, "EXPERIAN", f, calc.rate(f),
                LocalDate.of(2026, 8, 22), raw);
        assertThat(extractText(experianPdf)).contains("Complete Provider Response");
    }

    private static String extractText(byte[] pdf) throws Exception {
        PdfReader reader = new PdfReader(pdf);
        StringBuilder text = new StringBuilder();
        for (int page = 1; page <= reader.getNumberOfPages(); page++) {
            text.append(new PdfTextExtractor(reader).getTextFromPage(page));
        }
        reader.close();
        return text.toString().replaceAll("\\s+", " ");
    }
}
