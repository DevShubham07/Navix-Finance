package com.navix.loan.pdf;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.lowagie.text.pdf.draw.LineSeparator;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * The borrower's closure document for a CLOSED loan: a <b>No Dues Certificate</b> when the loan was
 * repaid in full, or a <b>Settlement Closure Letter</b> when an approved full &amp; final settlement
 * closed it. Emailed with LOAN_CLOSED and downloadable from Past loans. Pure / stateless, rendered on
 * demand; dated the loan's closing day so every render reads the same.
 *
 * <p>Amounts carry an "Rs " prefix: the base-14 PDF fonts have no ₹ glyph.
 */
@Component
public class ClosureCertificatePdfRenderer {

    private static final Color NAVY = new Color(10, 37, 64);
    private static final Color GREY = new Color(120, 130, 140);
    private static final Color INK = new Color(33, 43, 54);
    private static final Color LINE = new Color(190, 196, 204);

    private static final Font WORDMARK = new Font(Font.HELVETICA, 17, Font.BOLD, NAVY);
    private static final Font TITLE = new Font(Font.HELVETICA, 10, Font.BOLD, GREY);
    private static final Font HEADING = new Font(Font.HELVETICA, 15, Font.BOLD, NAVY);
    private static final Font META = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, GREY);
    private static final Font LABEL = new Font(Font.HELVETICA, 9, Font.NORMAL, GREY);
    private static final Font VALUE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, INK);
    private static final Font BODY = new Font(Font.HELVETICA, 10, Font.NORMAL, INK);
    private static final Font SIGN = new Font(Font.HELVETICA, 9.5f, Font.BOLD, NAVY);
    private static final Font FOOTER = new Font(Font.HELVETICA, 8, Font.ITALIC, GREY);

    /** The DhanBoost mark (copy of frontend/public/dhanboost-mark.png); null if missing → text-only header. */
    private static final byte[] LOGO = loadLogo();

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /**
     * @param settledAmountPaise the approved settlement figure, or {@code null} for a loan repaid in full
     */
    public byte[] render(long loanId, Long applicationId, String borrowerName, long principalPaise,
                         LocalDate disbursedOn, LocalDate closedOn, long totalPaidPaise,
                         Long settledAmountPaise) {
        boolean settled = settledAmountPaise != null;
        String name = borrowerName == null || borrowerName.isBlank() ? "the borrower" : borrowerName;
        Document doc = new Document(PageSize.A4, 54, 54, 36, 30);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SimulationStamp.apply(PdfWriter.getInstance(doc, out));
            doc.open();

            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setWidths(new float[] {2f, 1.4f});
            header.addCell(brand());
            PdfPCell docTitle = borderless(new Phrase(settled ? "SETTLEMENT CLOSURE LETTER" : "NO DUES CERTIFICATE",
                    TITLE), Element.ALIGN_RIGHT);
            docTitle.setVerticalAlignment(Element.ALIGN_MIDDLE);
            header.addCell(docTitle);
            doc.add(header);
            doc.add(rule());

            doc.add(spaced(new Paragraph("Ref: DB/" + (settled ? "SCL" : "NDC") + "/" + loanId
                    + "    Date: " + date(closedOn), META), 10, 14));

            Paragraph title = new Paragraph(settled ? "Settlement Closure Letter" : "No Dues Certificate", HEADING);
            title.setAlignment(Element.ALIGN_CENTER);
            doc.add(spaced(title, 0, 4));
            Paragraph subtitle = new Paragraph("To whomsoever it may concern", META);
            subtitle.setAlignment(Element.ALIGN_CENTER);
            doc.add(spaced(subtitle, 0, 14));

            String body = settled
                    ? "This is to certify that " + name + " has paid " + rs(settledAmountPaise)
                            + " towards loan account no. " + loanId + " as a full and final settlement "
                            + "approved by us. The loan account was closed on " + date(closedOn)
                            + " and no further amount is payable by the borrower under this loan."
                    : "This is to certify that " + name + " has repaid loan account no. " + loanId
                            + " in full, together with all applicable interest and charges. There are no "
                            + "dues outstanding under this loan, and the loan account was closed on "
                            + date(closedOn) + ".";
            Paragraph p = new Paragraph(body, BODY);
            p.setLeading(15);
            p.setAlignment(Element.ALIGN_JUSTIFIED);
            doc.add(spaced(p, 0, 14));

            PdfPTable kv = new PdfPTable(2);
            kv.setWidthPercentage(100);
            kv.setWidths(new float[] {1.4f, 1f});
            row(kv, "Borrower", name);
            row(kv, "Loan account no.", String.valueOf(loanId));
            row(kv, "Application no.", applicationId == null ? "—" : String.valueOf(applicationId));
            row(kv, "Loan amount (principal)", rs(principalPaise));
            row(kv, "Disbursed on", date(disbursedOn));
            row(kv, settled ? "Settlement amount" : "Total amount repaid",
                    rs(settled ? settledAmountPaise : totalPaidPaise));
            row(kv, "Closed on", date(closedOn));
            row(kv, "Outstanding balance", rs(0));
            doc.add(kv);

            doc.add(spaced(new Paragraph("For NAVIX Finance Private Limited (DhanBoost)", SIGN), 30, 18));
            doc.add(new Paragraph("Authorised Signatory", BODY));
            doc.add(spaced(new Paragraph("This is a computer-generated document and does not require a "
                    + "physical signature.", META), 4, 24));

            doc.add(rule());
            Paragraph footer = new Paragraph(
                    "DhanBoost is operated by NAVIX Finance Private Limited (CIN U64990HR2026PTC144926) as "
                            + "technology service provider. Lender: MLB Securities Private Limited "
                            + "(CIN U74899DL1995PTC067535, RBI Registration No. 14.00526).", FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render closure certificate PDF", e);
        }
    }

    /** Logo + "DhanBoost" wordmark side by side, vertically centred. */
    private static PdfPCell brand() throws DocumentException {
        if (LOGO == null) {
            return borderless(new Phrase("DhanBoost", WORDMARK), Element.ALIGN_LEFT);
        }
        PdfPTable inner = new PdfPTable(2);
        inner.setWidthPercentage(100);
        inner.setWidths(new float[] {0.32f, 2f});
        try {
            Image mark = Image.getInstance(LOGO);
            mark.scaleToFit(34, 34);
            PdfPCell logo = new PdfPCell(mark, false);
            logo.setBorder(Rectangle.NO_BORDER);
            logo.setVerticalAlignment(Element.ALIGN_MIDDLE);
            inner.addCell(logo);
        } catch (java.io.IOException e) {
            return borderless(new Phrase("DhanBoost", WORDMARK), Element.ALIGN_LEFT);
        }
        PdfPCell word = borderless(new Phrase("DhanBoost", WORDMARK), Element.ALIGN_LEFT);
        word.setVerticalAlignment(Element.ALIGN_MIDDLE);
        word.setPaddingLeft(6);
        inner.addCell(word);
        PdfPCell holder = new PdfPCell(inner);
        holder.setBorder(Rectangle.NO_BORDER);
        holder.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return holder;
    }

    private static byte[] loadLogo() {
        try (InputStream in = ClosureCertificatePdfRenderer.class.getResourceAsStream("/brand/dhanboost-mark.png")) {
            return in == null ? null : in.readAllBytes();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    private static void row(PdfPTable t, String label, String value) {
        t.addCell(kvCell(new Phrase(label, LABEL)));
        t.addCell(kvCell(new Phrase(value, VALUE)));
    }

    private static PdfPCell kvCell(Phrase p) {
        PdfPCell c = new PdfPCell(p);
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderColor(LINE);
        c.setBorderWidth(0.4f);
        c.setPaddingTop(4);
        c.setPaddingBottom(4);
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
        p.add(new Chunk(new LineSeparator(0.8f, 100, LINE, Element.ALIGN_CENTER, -2)));
        p.setSpacingBefore(4);
        return p;
    }

    private static Paragraph spaced(Paragraph p, float before, float after) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    /** Rupees with paise (a certificate states the exact figure), Indian digit grouping. */
    private static String rs(long paise) {
        NumberFormat in = NumberFormat.getInstance(new Locale("en", "IN"));
        in.setMinimumFractionDigits(2);
        in.setMaximumFractionDigits(2);
        return "Rs " + in.format(BigDecimal.valueOf(paise).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
    }

    private static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }
}
