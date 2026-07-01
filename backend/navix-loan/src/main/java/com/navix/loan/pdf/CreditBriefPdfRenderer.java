package com.navix.loan.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPCellEvent;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import com.navix.common.verification.BureauReportFacts;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders the one-page, NAVIX-branded credit brief to PDF bytes (OpenPDF). Pure / stateless.
 *
 * <p>Layout: wordmark + title header, customer line, the 1–5★ recommendation (drawn as vector
 * star polygons — no font-glyph dependency), credit score, the spec's three categories (A Identity /
 * B Credit Health / C Exposure) as columns, the underwriter summary, and a confidential footer.
 * Amounts use a "Rs " prefix (the base-14 PDF fonts have no ₹ glyph), Indian digit-grouped.
 */
@Component
public class CreditBriefPdfRenderer {

    private static final Color NAVY = new Color(10, 37, 64);
    private static final Color GOLD = new Color(212, 160, 23);
    private static final Color GREY = new Color(120, 130, 140);
    private static final Color LIGHT = new Color(210, 214, 220);
    private static final Color LINE = new Color(190, 196, 204);

    private static final Font WORDMARK = new Font(Font.HELVETICA, 17, Font.BOLD, NAVY);
    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, GREY);
    private static final Font NAME = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
    private static final Font META = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, GREY);
    private static final Font RATING = new Font(Font.HELVETICA, 14, Font.BOLD, NAVY);
    private static final Font SCORE = new Font(Font.HELVETICA, 11, Font.NORMAL, NAVY);
    private static final Font SECTION = new Font(Font.HELVETICA, 9, Font.BOLD, NAVY);
    private static final Font LABEL = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, GREY);
    private static final Font VALUE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, new Color(33, 43, 54));
    private static final Font BODY = new Font(Font.HELVETICA, 10, Font.NORMAL, new Color(33, 43, 54));
    private static final Font FOOTER = new Font(Font.HELVETICA, 8, Font.ITALIC, GREY);

    private static final NumberFormat IN = NumberFormat.getInstance(new Locale("en", "IN"));

    /** Render the brief. {@code generatedOn} is supplied by the caller (keeps the renderer pure). */
    public byte[] render(long applicationId, Long customerId, String bureauSource,
                         BureauReportFacts f, Rating rating, LocalDate generatedOn) {
        Document doc = new Document(PageSize.A4, 42, 42, 40, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(headerTable());
            doc.add(rule());

            doc.add(spaced(new Paragraph(safe(titleCase(f.name()), "Customer"), NAME), 6, 0));
            doc.add(new Paragraph(
                    "Application #" + applicationId
                            + (customerId != null ? "  ·  Customer #" + customerId : ""), META));
            doc.add(new Paragraph(
                    "Generated " + generatedOn + "  ·  Bureau: " + safe(bureauSource, "EXPERIAN")
                            + (f.reportNumber() != null ? "  ·  Report " + f.reportNumber() : ""), META));

            doc.add(ratingBlock(rating));

            doc.add(categories(f));

            doc.add(spaced(new Paragraph("Underwriter Summary", SECTION), 8, 2));
            doc.add(new Paragraph(pdfSafe(rating.summary()), BODY));

            doc.add(footerSpacer());
            doc.add(rule());
            Paragraph footer = new Paragraph(
                    "Confidential — NAVIX Finance internal underwriting brief.  Generated " + generatedOn
                            + ".  Bureau data is provided as-is for credit decisioning.", FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render credit-brief PDF", e);
        }
    }

    private PdfPTable headerTable() throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {2f, 1f});
        t.addCell(borderless(new Phrase("NAVIX FINANCE", WORDMARK), Element.ALIGN_LEFT));
        t.addCell(borderless(new Phrase("CREDIT BRIEF", TITLE), Element.ALIGN_RIGHT));
        return t;
    }

    private PdfPTable ratingBlock(Rating rating) {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        t.setSpacingBefore(10);

        PdfPCell stars = new PdfPCell(new Phrase(" "));
        stars.setBorder(Rectangle.NO_BORDER);
        stars.setFixedHeight(26);
        stars.setCellEvent(new StarRowEvent(rating.stars()));
        t.addCell(stars);

        PdfPCell verdict = new PdfPCell(new Phrase(
                String.format(Locale.ROOT, "%.1f / 5   —   %s", rating.stars(), rating.recommendation()),
                RATING));
        verdict.setBorder(Rectangle.NO_BORDER);
        verdict.setPaddingTop(2);
        t.addCell(verdict);
        return t;
    }

    private PdfPTable categories(BureauReportFacts f) throws DocumentException {
        PdfPTable t = new PdfPTable(3);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1.1f, 1f, 1.2f});
        t.setSpacingBefore(10f);
        t.setSpacingAfter(4f);

        t.addCell(category("A · Identity", new String[][] {
                {"Name", titleCase(f.name())},
                {"PAN", f.pan()},
                {"Mobile", f.mobile()},
                {"DOB", f.dob()},
                {"City", f.city()},
                {"PIN", f.pin()}}));

        t.addCell(category("B · Credit Health", new String[][] {
                {"Score", num(f.creditScore())},
                {"Total accounts", num(f.totalAccounts())},
                {"Active", num(f.activeAccounts())},
                {"Closed", num(f.closedAccounts())},
                {"Defaults", num(f.defaults())}}));

        long total = nz(f.totalBalanceRupees());
        long secured = nz(f.securedBalanceRupees());
        long unsecured = nz(f.unsecuredBalanceRupees());
        String securedPct = total > 0 ? " (" + Math.round(secured * 100.0 / total) + "%)" : "";
        String unsecPct = total > 0 ? " (" + Math.round(unsecured * 100.0 / total) + "%)" : "";
        t.addCell(category("C · Exposure", new String[][] {
                {"Total", rs(f.totalBalanceRupees())},
                {"Secured", rs(f.securedBalanceRupees()) + securedPct},
                {"Unsecured", rs(f.unsecuredBalanceRupees()) + unsecPct},
                {"Inquiries (30d)", num(f.recentInquiries30d())}}));
        return t;
    }

    private PdfPCell category(String title, String[][] rows) throws DocumentException {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingRight(8);

        Paragraph head = new Paragraph(title, SECTION);
        head.setSpacingAfter(3);
        cell.addElement(head);

        PdfPTable kv = new PdfPTable(2);
        kv.setWidthPercentage(100);
        kv.setWidths(new float[] {1.1f, 1.4f});
        for (String[] row : rows) {
            kv.addCell(kvCell(new Phrase(row[0], LABEL)));
            kv.addCell(kvCell(new Phrase(safe(row[1], "—"), VALUE)));
        }
        cell.addElement(kv);
        return cell;
    }

    private static PdfPCell kvCell(Phrase p) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPaddingTop(1.5f);
        c.setPaddingBottom(1.5f);
        return c;
    }

    private static PdfPCell borderless(Phrase p, int align) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setHorizontalAlignment(align);
        return c;
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph();
        p.add(new com.lowagie.text.Chunk(new LineSeparator(0.8f, 100, LINE, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(4);
        p.setSpacingAfter(2);
        return p;
    }

    private static Paragraph footerSpacer() {
        Paragraph p = new Paragraph(" ", FOOTER);
        p.setSpacingBefore(14);
        return p;
    }

    private static Paragraph spaced(Paragraph p, float before, float after) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    /** Star row drawn as vector polygons so it needs no special font. */
    private static final class StarRowEvent implements PdfPCellEvent {
        private final double stars;

        StarRowEvent(double stars) {
            this.stars = stars;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle pos, PdfContentByte[] canvases) {
            PdfContentByte cb = canvases[PdfPTable.LINECANVAS];
            float outer = 9f;
            float gap = outer * 2.5f;
            float cy = pos.getBottom() + pos.getHeight() / 2f;
            float startX = pos.getLeft() + outer + 2f;
            for (int i = 0; i < 5; i++) {
                double remaining = stars - i;
                float fill = remaining >= 1 ? 1f : (remaining >= 0.5 ? 0.5f : 0f);
                drawStar(cb, startX + i * gap, cy, outer, fill);
            }
        }

        private void drawStar(PdfContentByte cb, float cx, float cy, float outer, float fill) {
            float inner = outer * 0.40f;
            starPath(cb, cx, cy, outer, inner);
            cb.setColorFill(LIGHT);
            cb.fill();
            if (fill <= 0f) {
                return;
            }
            cb.saveState();
            if (fill < 1f) {
                cb.rectangle(cx - outer, cy - outer, outer, 2 * outer);
                cb.clip();
                cb.newPath();
            }
            starPath(cb, cx, cy, outer, inner);
            cb.setColorFill(GOLD);
            cb.fill();
            cb.restoreState();
        }

        private void starPath(PdfContentByte cb, float cx, float cy, float outer, float inner) {
            for (int i = 0; i < 5; i++) {
                double ao = -Math.PI / 2 + i * 2 * Math.PI / 5;
                double ai = ao + Math.PI / 5;
                float ox = cx + (float) (outer * Math.cos(ao));
                float oy = cy + (float) (outer * Math.sin(ao));
                float ix = cx + (float) (inner * Math.cos(ai));
                float iy = cy + (float) (inner * Math.sin(ai));
                if (i == 0) {
                    cb.moveTo(ox, oy);
                } else {
                    cb.lineTo(ox, oy);
                }
                cb.lineTo(ix, iy);
            }
            cb.closePath();
        }
    }

    // ---- formatting helpers ----

    private static String rs(Long rupees) {
        if (rupees == null) {
            return "—";
        }
        synchronized (IN) {
            return "Rs " + IN.format(rupees);
        }
    }

    private static String num(Integer v) {
        return v == null ? "—" : String.valueOf(v);
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }

    private static String safe(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }

    /** The base-14 PDF fonts have no ₹ glyph — substitute for any text harvested from elsewhere. */
    private static String pdfSafe(String s) {
        return s == null ? "" : s.replace("₹", "Rs ");
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String[] parts = s.trim().toLowerCase(Locale.ROOT).split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }
}
