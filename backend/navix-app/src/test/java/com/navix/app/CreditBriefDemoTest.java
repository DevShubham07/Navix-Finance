package com.navix.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.pdf.CreditBriefPdfRenderer;
import com.navix.loan.service.CreditRatingCalculator;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import com.navix.verification.client.SignzyExperianClient;
import com.navix.verification.dto.SignzyDtos.ExperianResponse;
import com.navix.verification.support.CrifHighmarkFactsParser;
import java.io.File;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Demo runner (prints output; no assertions): parses the bundled {@code samplepan.json} (Experian)
 * and {@code crif-combine-sample.json} (Fintrix CRIF) through the real bureau parsers, runs the
 * 1–5★ rating, renders the one-page PDF, and writes a readable report + the PDF under
 * {@code navix-app/target/}. Run with: {@code -Dtest=CreditBriefDemoTest} (both cases), or
 * {@code -Dtest=CreditBriefDemoTest#runOnCrifSample} for just the new score-trend/top-exposures
 * sections — that's the fast visual loop for wave 4's PDF changes; open
 * {@code navix-app/target/credit-brief-crif-sample.pdf} afterwards.
 */
class CreditBriefDemoTest {

    @Test
    void runOnSampleReport() throws Exception {
        ExperianResponse r = new SignzyExperianClient(RestClient.create(), new ObjectMapper(),
                "classpath:samplepan.json", "3.109.169.131").pull("DEMO", "DEMO DEMO", "DEMO", "1990-01-01");
        BureauReportFacts f = r.facts();
        Rating rating = new CreditRatingCalculator().rate(f);

        String report = """
                ================ DhanBoost CREDIT BRIEF — samplepan.json ================
                Bureau response : score=%s  noRecord=%s  report=%s

                CATEGORY A — Identity
                  Name            : %s
                  PAN             : %s
                  Mobile          : %s
                  DOB             : %s
                  City / PIN      : %s / %s

                CATEGORY B — Credit health
                  Credit score    : %s
                  Accounts (total): %s
                  Active          : %s
                  Closed          : %s
                  Defaults        : %s

                CATEGORY C — Exposure (rupees)
                  Total balance   : %s
                  Secured         : %s
                  Unsecured       : %s
                  Inquiries (30d) : %s

                RATING
                  Stars           : %s / 5
                  Recommendation  : %s
                  Summary         : %s
                ====================================================================
                """.formatted(
                r.creditScore(), r.noRecord(), f.reportNumber(),
                f.name(), f.pan(), f.mobile(), f.dob(), f.city(), f.pin(),
                f.creditScore(), f.totalAccounts(), f.activeAccounts(), f.closedAccounts(), f.defaults(),
                f.totalBalanceRupees(), f.securedBalanceRupees(), f.unsecuredBalanceRupees(),
                f.recentInquiries30d(),
                rating.stars(), rating.recommendation(), rating.summary());

        System.out.println(report);

        File outDir = new File("target");
        outDir.mkdirs();
        File txt = new File(outDir, "credit-brief-sample.txt");
        Files.writeString(txt.toPath(), report);

        byte[] pdf = new CreditBriefPdfRenderer()
                .render(123L, 45L, "EXPERIAN", f, rating, LocalDate.of(2026, 6, 28),
                        r.rawResponseJson());
        File pdfFile = new File(outDir, "credit-brief-sample.pdf");
        Files.write(pdfFile.toPath(), pdf);

        System.out.println("WROTE " + txt.getAbsolutePath());
        System.out.println("WROTE " + pdfFile.getAbsolutePath()
                + " (" + pdf.length + " bytes, header=" + new String(pdf, 0, 5) + ")");
    }

    /** Same idea, but through the Fintrix CRIF parser — exercises the score-trend sparkline, the
     *  top-exposures strip, and the "no raw appendix for CRIF" behaviour added in wave 4. */
    @Test
    void runOnCrifSample() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode envelope;
        try (InputStream in = getClass().getResourceAsStream("/crif-combine-sample.json")) {
            envelope = mapper.readTree(in);
        }
        JsonNode report = envelope.at("/canonical/data/credit_report");
        BureauReportFacts f = CrifHighmarkFactsParser.parse(report, 799, "SAMPLE PERSON", "ABCDE1234F", "9000000001");
        Rating rating = new CreditRatingCalculator().rate(f);

        System.out.println("""
                ================ DhanBoost CREDIT BRIEF — crif-combine-sample.json ================
                score=%s  tradelines=%s  scoreHistoryPoints=%s  stars=%s/5  %s
                ====================================================================
                """.formatted(
                f.creditScore(),
                f.detail() != null ? f.detail().tradelines().size() : 0,
                f.detail() != null && f.detail().scoreHistory() != null
                        ? f.detail().scoreHistory().points().size() : 0,
                rating.stars(), rating.recommendation()));

        File outDir = new File("target");
        outDir.mkdirs();
        byte[] pdf = new CreditBriefPdfRenderer()
                .render(456L, 78L, "FINTRIX_CRIF", f, rating, LocalDate.of(2026, 8, 22), envelope.toString());
        File pdfFile = new File(outDir, "credit-brief-crif-sample.pdf");
        Files.write(pdfFile.toPath(), pdf);

        System.out.println("WROTE " + pdfFile.getAbsolutePath()
                + " (" + pdf.length + " bytes, header=" + new String(pdf, 0, 5) + ")");
    }
}
