package com.navix.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.pdf.CreditBriefPdfRenderer;
import com.navix.loan.service.CreditRatingCalculator;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import com.navix.verification.client.ExperianClient;
import com.navix.verification.dto.FintrixDtos.ExperianResponse;
import java.io.File;
import java.nio.file.Files;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/**
 * Demo runner (prints output; no assertions): parses the bundled {@code samplepan.json} through the
 * real bureau parser, runs the 1–5★ rating, renders the one-page PDF, and writes a readable report
 * + the PDF under {@code navix-app/target/}. Run with: {@code -Dtest=CreditBriefDemoTest}.
 */
class CreditBriefDemoTest {

    @Test
    void runOnSampleReport() throws Exception {
        ExperianResponse r = new ExperianClient(RestClient.create(), new ObjectMapper(),
                "classpath:samplepan.json").pull("DEMO", "DEMO", "DEMO", "demo");
        BureauReportFacts f = r.facts();
        Rating rating = new CreditRatingCalculator().rate(f);

        String report = """
                ================ NAVIX CREDIT BRIEF — samplepan.json ================
                Bureau response : status=%s  noRecord=%s  report=%s

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
                r.status(), r.noRecord(), f.reportNumber(),
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
                .render(123L, 45L, "EXPERIAN", f, rating, LocalDate.of(2026, 6, 28));
        File pdfFile = new File(outDir, "credit-brief-sample.pdf");
        Files.write(pdfFile.toPath(), pdf);

        System.out.println("WROTE " + txt.getAbsolutePath());
        System.out.println("WROTE " + pdfFile.getAbsolutePath()
                + " (" + pdf.length + " bytes, header=" + new String(pdf, 0, 5) + ")");
    }
}
