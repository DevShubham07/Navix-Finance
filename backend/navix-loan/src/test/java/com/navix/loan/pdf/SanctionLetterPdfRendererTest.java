package com.navix.loan.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import javax.imageio.ImageIO;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;

/**
 * Smoke test for the Key Fact Statement: a valid (non-empty, %PDF-headed) document for a complete
 * set of facts and for a sparse one. Sparse matters here — the borrower reaches this screen before
 * DigiLocker has necessarily filled in their identity, so the letter must render without it rather
 * than throw on the way to the screen they have to sign.
 */
class SanctionLetterPdfRendererTest {

    private final SanctionLetterPdfRenderer renderer = new SanctionLetterPdfRenderer();

    @Test
    void rendersValidPdfForACompleteStatement() {
        // ₹15,000 over 30 days: fee 1,500 · GST 270 · net 13,230 · interest 4,500 · total 19,500.
        SanctionLetterPdfRenderer.Facts f = new SanctionLetterPdfRenderer.Facts(
                54L, "Asha Kumari", "AZKPQ3156X", "9811100948",
                1_500_000L, 150_000L, 27_000L, 1_323_000L, 450_000L, 1_950_000L, 30,
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 6), "•••• 9012",
                "Lalit Kumar", "9716760246", "grievance@dhanboost.com");

        byte[] pdf = renderer.render(f);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void rendersWhenIdentityAndAccountAreNotYetKnown() {
        SanctionLetterPdfRenderer.Facts f = new SanctionLetterPdfRenderer.Facts(
                1L, null, null, null,
                100_000L, 10_000L, 1_800L, 88_200L, 30_000L, 130_000L, 30,
                null, null, null, null, null, null);

        byte[] pdf = renderer.render(f);

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void signedAgreementContainsSignatureNameAndServerTimestamp() throws Exception {
        SanctionLetterPdfRenderer.Facts f = new SanctionLetterPdfRenderer.Facts(
                54L, "Asha Kumari", "AZKPQ3156X", "9811100948",
                1_500_000L, 150_000L, 27_000L, 1_323_000L, 450_000L, 1_950_000L, 30,
                LocalDate.of(2026, 8, 7), LocalDate.of(2026, 9, 6), "•••• 9012",
                "Lalit Kumar", "9716760246", "grievance@dhanboost.com");
        BufferedImage signature = new BufferedImage(180, 50, BufferedImage.TYPE_INT_ARGB);
        signature.setRGB(20, 20, 0xff0c2540);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(signature, "png", png);

        byte[] pdf = renderer.renderSigned(f, png.toByteArray(), "Asha Kumari",
                Instant.parse("2026-08-09T13:17:18Z"));

        PdfReader reader = new PdfReader(pdf);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);
        assertThat(text).contains("Borrower signature", "Signed by Asha Kumari",
                "9 Aug 2026", "IST", "Application #54");
        assertThat(reader.getPageN(1).getAsDict(com.lowagie.text.pdf.PdfName.RESOURCES)
                .getAsDict(com.lowagie.text.pdf.PdfName.XOBJECT).size()).isGreaterThan(0);
        reader.close();
    }
}
