package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.service.CreditRatingCalculator;
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
}
