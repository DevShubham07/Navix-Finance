package com.navix.loan.pdf;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
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
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Renders the sanction letter — a full <b>Key Fact Statement</b> (revamp.md decision 38) — to PDF
 * bytes (OpenPDF). Pure / stateless, same shape as {@link CreditBriefPdfRenderer}.
 *
 * <p>This is the document the borrower reads on {@code /loan/sanction-letter} and eSigns on
 * {@code /loan/esign}, so it must state <em>everything</em> that binds them: the amount, the fee and
 * GST deducted upfront, what actually lands in their account, the interest rate and tenure, the due
 * date, the total repayable, and the late-penalty and prepayment terms — plus the grievance officer.
 *
 * <p>Amounts carry an "Rs " prefix: the base-14 PDF fonts have no ₹ glyph, and substituting one would
 * silently drop the character.
 */
@Component
public class SanctionLetterPdfRenderer {

    private static final Color NAVY = new Color(10, 37, 64);
    private static final Color GREY = new Color(120, 130, 140);
    private static final Color INK = new Color(33, 43, 54);
    private static final Color LINE = new Color(190, 196, 204);
    private static final Color BAND = new Color(240, 244, 248);

    private static final Font WORDMARK = new Font(Font.HELVETICA, 17, Font.BOLD, NAVY);
    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, GREY);
    private static final Font NAME = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
    private static final Font META = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, GREY);
    private static final Font SECTION = new Font(Font.HELVETICA, 9.5f, Font.BOLD, NAVY);
    private static final Font LABEL = new Font(Font.HELVETICA, 9, Font.NORMAL, GREY);
    private static final Font VALUE = new Font(Font.HELVETICA, 9.5f, Font.BOLD, INK);
    private static final Font HERO_LABEL = new Font(Font.HELVETICA, 9, Font.NORMAL, NAVY);
    private static final Font HERO_VALUE = new Font(Font.HELVETICA, 13, Font.BOLD, NAVY);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font FOOTER = new Font(Font.HELVETICA, 8, Font.ITALIC, GREY);

    private static final NumberFormat IN = NumberFormat.getInstance(new Locale("en", "IN"));
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    /** Everything the letter states. Assembled by the caller so this class stays free of repositories. */
    public record Facts(
            long applicationId,
            String borrowerName,
            String pan,
            String mobile,
            long principalPaise,
            long processingFeePaise,
            long gstPaise,
            long netDisbursedPaise,
            long interestPaise,
            long totalRepayablePaise,
            int tenureDays,
            LocalDate sanctionedOn,
            LocalDate repaymentDate,
            String disbursalAccountMasked,
            String grievanceOfficerName,
            String grievanceOfficerPhone,
            String grievanceOfficerEmail) {
    }

    public byte[] render(Facts f) {
        Document doc = new Document(PageSize.A4, 44, 44, 40, 40);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            doc.add(header());
            doc.add(rule());

            doc.add(spaced(new Paragraph(safe(f.borrowerName(), "Customer"), NAME), 8, 0));
            doc.add(new Paragraph("Application #" + f.applicationId()
                    + (blank(f.pan()) ? "" : "  ·  PAN " + f.pan())
                    + (blank(f.mobile()) ? "" : "  ·  " + f.mobile()), META));
            doc.add(new Paragraph("Sanctioned on " + date(f.sanctionedOn()), META));

            doc.add(hero(f));

            doc.add(spaced(new Paragraph("Key Facts", SECTION), 12, 3));
            doc.add(kvTable(new String[][] {
                    {"Loan amount (principal)", rs(f.principalPaise())},
                    {"Processing fee (excluding GST)", rs(f.processingFeePaise())},
                    {"GST on processing fee (18%)", rs(f.gstPaise())},
                    {"Amount credited to your account", rs(f.netDisbursedPaise())},
                    {"Interest rate", "1% per day on the principal"},
                    {"Tenure", f.tenureDays() + " days"},
                    {"Interest payable over the tenure", rs(f.interestPaise())},
                    {"Repayment date", date(f.repaymentDate())},
                    {"Total repayable on the repayment date", rs(f.totalRepayablePaise())},
                    {"Number of instalments", "1 (single repayment)"},
                    {"Credit account", safe(f.disbursalAccountMasked(), "As confirmed by you")},
            }));

            doc.add(spaced(new Paragraph("Charges and terms", SECTION), 12, 3));
            doc.add(bullets(new String[] {
                    "The processing fee and GST are deducted upfront from the sanctioned amount. "
                            + "You receive " + rs(f.netDisbursedPaise()) + "; you repay "
                            + rs(f.totalRepayablePaise()) + ".",
                    "Interest accrues at 1% per day on the principal for the actual number of days the "
                            + "advance is held.",
                    "Prepayment is allowed at any time at no charge. Interest is charged only to the day "
                            + "you pay, so repaying early reduces what you owe.",
                    "If the advance is not repaid by the repayment date, a late penalty of 2% per day on "
                            + "the principal applies from the day after the date shown above, capped at 30 days.",
                    "Repayment is a single instalment. There is no auto-debit mandate on this advance.",
                    "This sanction is subject to successful verification and to the funds being released; "
                            + "it does not itself create a debt until the amount is credited to you.",
            }));

            doc.add(spaced(new Paragraph("Grievance redressal", SECTION), 12, 3));
            doc.add(new Paragraph(
                    "Grievance Redressal Officer: " + safe(f.grievanceOfficerName(), "—")
                            + "  ·  " + safe(f.grievanceOfficerPhone(), "—")
                            + "  ·  " + safe(f.grievanceOfficerEmail(), "—")
                            + ". If your complaint is not resolved within 30 days you may escalate it to "
                            + "the RBI Ombudsman.", BODY));

            doc.add(footerSpacer());
            doc.add(rule());
            Paragraph footer = new Paragraph(
                    "NAVIX Finance Private Limited (CIN U64990HR2026PTC144926), operating as DhanBoost.  "
                            + "This Key Fact Statement forms part of your loan agreement and is signed "
                            + "electronically by you.", FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render sanction-letter PDF", e);
        }
    }

    private PdfPTable header() throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {2f, 1.4f});
        t.addCell(borderless(new Phrase("DhanBoost", WORDMARK), Element.ALIGN_LEFT));
        t.addCell(borderless(new Phrase("SANCTION LETTER  ·  KEY FACT STATEMENT", TITLE),
                Element.ALIGN_RIGHT));
        return t;
    }

    /** The two numbers the borrower actually cares about, banded so they can't be missed. */
    private PdfPTable hero(Facts f) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1f, 1f});
        t.setSpacingBefore(12);
        t.addCell(heroCell("You will receive", rs(f.netDisbursedPaise())));
        t.addCell(heroCell("You will repay on " + date(f.repaymentDate()), rs(f.totalRepayablePaise())));
        return t;
    }

    private static PdfPCell heroCell(String label, String value) {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(BAND);
        c.setPadding(10);
        Paragraph l = new Paragraph(label, HERO_LABEL);
        l.setSpacingAfter(2);
        c.addElement(l);
        c.addElement(new Paragraph(value, HERO_VALUE));
        return c;
    }

    private PdfPTable kvTable(String[][] rows) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1.6f, 1f});
        for (String[] row : rows) {
            t.addCell(kvCell(new Phrase(row[0], LABEL)));
            t.addCell(kvCell(new Phrase(safe(row[1], "—"), VALUE)));
        }
        return t;
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

    private Paragraph bullets(String[] lines) {
        Paragraph block = new Paragraph();
        for (String line : lines) {
            Paragraph p = new Paragraph("•  " + line, BODY);
            p.setSpacingAfter(4);
            p.setIndentationLeft(4);
            block.add(p);
        }
        return block;
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
        return p;
    }

    private static Paragraph footerSpacer() {
        Paragraph p = new Paragraph(" ", FOOTER);
        p.setSpacingBefore(10);
        return p;
    }

    private static Paragraph spaced(Paragraph p, float before, float after) {
        p.setSpacingBefore(before);
        p.setSpacingAfter(after);
        return p;
    }

    private static String rs(long paise) {
        return "Rs " + IN.format(Math.round(paise / 100.0));
    }

    private static String date(LocalDate d) {
        return d == null ? "—" : DATE.format(d);
    }

    private static String safe(String v, String fallback) {
        return blank(v) ? fallback : v;
    }

    private static boolean blank(String v) {
        return v == null || v.isBlank();
    }
}
