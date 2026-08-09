package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.service.CreditRatingCalculator;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
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
}
