package com.navix.loan.pdf;

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
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
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
 * <p>Layout follows the DB-LGL-001 sanction-letter template (subject, approved terms, conditions,
 * disclosures, acceptance, lender, contacts), with every placeholder filled from the loan's own figures.
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
    private static final Font TITLE = new Font(Font.HELVETICA, 10, Font.BOLD, GREY);
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
    private static final DateTimeFormatter SIGNED_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, h:mm:ss a 'IST'", Locale.ENGLISH);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final String LENDER = "MLB Securities Private Limited";
    /** Room for the eSign stamp Signzy places bottom-left on every page. */
    private static final float BOTTOM_MARGIN = 90;

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
            String grievanceOfficerEmail,
            long feeDiscountPaise,
            String email,
            String address) {

        /** Shape without contact details (email / communication address). */
        public Facts(long applicationId, String borrowerName, String pan, String mobile, long principalPaise,
                     long processingFeePaise, long gstPaise, long netDisbursedPaise, long interestPaise,
                     long totalRepayablePaise, int tenureDays, LocalDate sanctionedOn, LocalDate repaymentDate,
                     String disbursalAccountMasked, String grievanceOfficerName, String grievanceOfficerPhone,
                     String grievanceOfficerEmail, long feeDiscountPaise) {
            this(applicationId, borrowerName, pan, mobile, principalPaise, processingFeePaise, gstPaise,
                    netDisbursedPaise, interestPaise, totalRepayablePaise, tenureDays, sanctionedOn,
                    repaymentDate, disbursalAccountMasked, grievanceOfficerName, grievanceOfficerPhone,
                    grievanceOfficerEmail, feeDiscountPaise, null, null);
        }

        /** Pre-discount shape: no repayment discount. */
        public Facts(long applicationId, String borrowerName, String pan, String mobile, long principalPaise,
                     long processingFeePaise, long gstPaise, long netDisbursedPaise, long interestPaise,
                     long totalRepayablePaise, int tenureDays, LocalDate sanctionedOn, LocalDate repaymentDate,
                     String disbursalAccountMasked, String grievanceOfficerName, String grievanceOfficerPhone,
                     String grievanceOfficerEmail) {
            this(applicationId, borrowerName, pan, mobile, principalPaise, processingFeePaise, gstPaise,
                    netDisbursedPaise, interestPaise, totalRepayablePaise, tenureDays, sanctionedOn,
                    repaymentDate, disbursalAccountMasked, grievanceOfficerName, grievanceOfficerPhone,
                    grievanceOfficerEmail, 0L);
        }
    }

    public byte[] render(Facts f) {
        return renderDocument(f, null, null, null);
    }

    /** Render the immutable signed copy with the borrower's actual signature and server timestamp. */
    public byte[] renderSigned(Facts f, byte[] signaturePng, String signerName, Instant signedAt) {
        if (signaturePng == null || signaturePng.length == 0 || signedAt == null) {
            throw new IllegalArgumentException("Signature bytes and signing time are required");
        }
        return renderDocument(f, signaturePng, signerName, signedAt);
    }

    private byte[] renderDocument(Facts f, byte[] signaturePng, String signerName, Instant signedAt) {
        // The tall bottom margin is deliberate: Signzy stamps the Aadhaar eSign at the bottom-left of
        // EVERY page (SignzyEsignAdapter, "All"/"BottomLeft"), so no text may run into that corner.
        Document doc = new Document(PageSize.A4, 44, 44, 26, BOTTOM_MARGIN);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            SimulationStamp.apply(PdfWriter.getInstance(doc, out));
            doc.open();

            doc.add(header());
            doc.add(rule());

            doc.add(spaced(new Paragraph("Subject: Sanction of Personal Loan — Application No. "
                    + f.applicationId(), SECTION), 8, 4));
            doc.add(new Paragraph("Dear " + safe(f.borrowerName(), "Customer") + ",", BODY));
            doc.add(spaced(new Paragraph("With reference to your application, " + LENDER + " (the lender) "
                    + "has approved the loan described below, subject to the conditions in this letter, this "
                    + "Key Facts Statement (KFS), the loan agreement and applicable law. This sanction is not a "
                    + "disbursement confirmation.", BODY), 3, 0));

            doc.add(hero(f));

            doc.add(spaced(new Paragraph("Sanction and application details", SECTION), 8, 3));
            doc.add(kvTable(new String[][] {
                    {"Issue date", date(f.sanctionedOn())},
                    {"Application No.", String.valueOf(f.applicationId())},
                    {"Loan account No.", "Assigned at disbursal"},
                    {"Borrower name", safe(f.borrowerName(), "As per your KYC")},
                    blank(f.pan()) ? null : new String[] {"PAN", f.pan()},
                    blank(f.mobile()) ? null : new String[] {"Registered mobile", f.mobile()},
                    blank(f.email()) ? null : new String[] {"Borrower email", f.email()},
                    blank(f.address()) ? null : new String[] {"Communication address", f.address()},
                    {"Lender", LENDER},
                    {"Offer validity", "This sanction does not expire"},
            }));

            doc.add(spaced(new Paragraph("1. Approved loan terms", SECTION), 8, 3));
            doc.add(kvTable(new String[][] {
                    {"Approved principal / loan amount", rs(f.principalPaise())},
                    f.feeDiscountPaise() > 0
                            ? new String[] {"Repayment discount applied", rs(f.feeDiscountPaise())} : null,
                    {"Tenure", f.tenureDays() + " days"},
                    {"Interest rate and calculation method",
                            "1% per day on the principal, simple interest, for the actual days held"},
                    {"Annual Percentage Rate (APR)", apr(f)},
                    {"Processing fee (excluding GST)", rs(f.processingFeePaise())},
                    {"GST on processing fee (18%)", rs(f.gstPaise())},
                    {"Other charges", "None"},
                    {"Total deductions from disbursal", rs(f.processingFeePaise() + f.gstPaise())},
                    {"Net amount to be disbursed", rs(f.netDisbursedPaise())},
                    {"Interest payable over the tenure", rs(f.interestPaise())},
                    {"Total amount payable on the repayment date", rs(f.totalRepayablePaise())},
                    {"Repayment frequency / instalments", "Single repayment (1 instalment)"},
                    {"Repayment due date", date(f.repaymentDate())},
                    {"Late / penal charges",
                            "2% per day on the principal from the day after the due date, capped at 30 days"},
                    {"Prepayment / foreclosure", "Allowed at any time at no charge"},
                    {"Authorised repayment method",
                            "Only via the Repay page in your DhanBoost account, or an e-mandate you authorise"},
                    {"Credit account", safe(f.disbursalAccountMasked(), "As confirmed by you")},
            }));

            doc.add(spaced(new Paragraph("2. Conditions before disbursement", SECTION), 8, 3));
            doc.add(bullets(new String[] {
                    "Completion of KYC, bank-account and other legally permitted verification.",
                    "Satisfactory creditworthiness, affordability and fraud-risk checks, as applicable.",
                    "Your review and acceptance of this KFS, the loan agreement, the repayment schedule and "
                            + "the required consents.",
                    "Valid execution of the agreement and of any authorised electronic repayment mandate, "
                            + "where applicable.",
                    "Confirmation of the permitted disbursal account and completion of lender formalities.",
            }));

            doc.add(spaced(new Paragraph("3. Important customer disclosures", SECTION), 8, 3));
            doc.add(bullets(new String[] {
                    "The processing fee and GST are deducted upfront from the sanctioned amount. "
                            + "You receive " + rs(f.netDisbursedPaise()) + "; you repay "
                            + rs(f.totalRepayablePaise()) + ".",
                    "Interest accrues at 1% per day on the principal for the actual number of days the "
                            + "advance is held. Interest is charged only to the day you pay, so repaying early "
                            + "reduces what you owe.",
                    "If the advance is not repaid by the repayment date, a late penalty of 2% per day on "
                            + "the principal applies from the day after the date shown above, capped at 30 days.",
                    "Information about this loan, including any late payment or default, may be reported by "
                            + "the lender to credit information companies. Late payment can lower your credit "
                            + "score, which those companies calculate.",
                    "This letter forms part of, and does not replace, the loan agreement and repayment "
                            + "schedule. If any term appears inconsistent, ask for corrected documents before "
                            + "accepting.",
                    "No fee or charge is collected unless it is lawfully applicable, approved by the lender "
                            + "and disclosed in this letter.",
                    "Repay only through the authorised channels shown in your DhanBoost account. Never pay "
                            + "an agent's personal account, and never share your OTP, password or UPI PIN.",
                    "Any extension, cancellation, prepayment or other change is subject to the loan "
                            + "documents, lender approval and applicable law.",
            }));

            doc.add(spaced(new Paragraph("4. Borrower acceptance", SECTION), 8, 3));
            doc.add(new Paragraph("I confirm that I have had an opportunity to review this sanction letter and "
                    + "Key Facts Statement. I understand that the loan will be disbursed only after the required "
                    + "conditions are satisfied and the applicable loan documents are accepted.", BODY));
            if (signaturePng != null) {
                doc.add(spaced(new Paragraph("Borrower signature", SECTION), 6, 3));
                doc.add(signatureBlock(f.applicationId(), signaturePng,
                        safe(signerName, f.borrowerName()), signedAt));
            } else {
                doc.add(spaced(new Paragraph("You accept this letter by signing it electronically in the "
                        + "DhanBoost app.", META), 3, 0));
            }

            doc.add(spaced(new Paragraph("5. For the lender", SECTION), 8, 3));
            doc.add(new Paragraph("Issued for and on behalf of " + LENDER + " through the DhanBoost platform "
                    + "operated by NAVIX Finance Private Limited. This is a system-generated letter.", BODY));

            doc.add(spaced(new Paragraph("Customer support and grievance redressal", SECTION), 8, 3));
            doc.add(new Paragraph(
                    "Website www.dhanboost.com  ·  Customer support 8510028510  ·  WhatsApp 9211331167  ·  "
                            + "support@dhanboost.com  ·  info@dhanboost.com", BODY));
            doc.add(spaced(new Paragraph(
                    "Grievance Redressal Officer: " + safe(f.grievanceOfficerName(), "—")
                            + "  ·  " + safe(f.grievanceOfficerPhone(), "—")
                            + "  ·  " + safe(f.grievanceOfficerEmail(), "—")
                            + ". If your complaint is not resolved within 30 days you may escalate it to "
                            + "the RBI Ombudsman.", BODY), 2, 0));

            doc.add(footerSpacer());
            doc.add(rule());
            Paragraph footer = new Paragraph(
                    "Lender: MLB Securities Private Limited (CIN U74899DL1995PTC067535, RBI Registration "
                            + "No. 14.00526). DhanBoost is operated by NAVIX Finance Private Limited "
                            + "(CIN U64990HR2026PTC144926) as technology service provider.  "
                            + "This Key Facts Statement forms part of your loan agreement and is signed "
                            + "electronically by you.", FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to render sanction-letter PDF", e);
        }
    }

    /** Interest plus processing fee on the principal less that fee, annualised. GST is excluded. */
    private static String apr(Facts f) {
        long netOfFee = f.netDisbursedPaise() + f.gstPaise();
        if (netOfFee <= 0 || f.tenureDays() <= 0) {
            return "—";
        }
        BigDecimal percent = BigDecimal.valueOf(f.totalRepayablePaise() - netOfFee)
                .multiply(BigDecimal.valueOf(36_500))
                .divide(BigDecimal.valueOf(netOfFee).multiply(BigDecimal.valueOf(f.tenureDays())),
                        1, RoundingMode.HALF_UP);
        return percent.toPlainString() + "% per year, including fee, excluding GST";
    }

    private PdfPTable signatureBlock(long applicationId, byte[] signaturePng,
                                     String signerName, Instant signedAt) throws DocumentException, IOException {
        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {1.1f, 1.4f});

        Image signature = Image.getInstance(signaturePng);
        signature.scaleToFit(150, 40);
        PdfPCell imageCell = new PdfPCell(signature, false);
        imageCell.setBorder(Rectangle.BOX);
        imageCell.setBorderColor(LINE);
        imageCell.setPadding(8);
        imageCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(imageCell);

        PdfPCell meta = new PdfPCell();
        meta.setBorder(Rectangle.BOX);
        meta.setBorderColor(LINE);
        meta.setPadding(8);
        meta.addElement(new Paragraph("Signed by " + safe(signerName, "Customer"), VALUE));
        meta.addElement(new Paragraph("Signed at " + SIGNED_TIME.format(signedAt.atZone(IST)), BODY));
        meta.addElement(new Paragraph("Application #" + applicationId, BODY));
        table.addCell(meta);
        return table;
    }

    private PdfPTable header() throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1.2f, 2f});
        t.addCell(borderless(new Phrase("DhanBoost", WORDMARK), Element.ALIGN_LEFT));
        t.addCell(borderless(new Phrase("LOAN SANCTION LETTER  ·  KEY FACTS STATEMENT", TITLE),
                Element.ALIGN_RIGHT));
        return t;
    }

    /** The two numbers the borrower actually cares about, banded so they can't be missed. */
    private PdfPTable hero(Facts f) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1f, 1f});
        t.setSpacingBefore(6);
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
            if (row == null) {
                continue; // optional row (e.g. the repayment discount) not applicable
            }
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
        c.setPaddingTop(2);
        c.setPaddingBottom(2);
        return c;
    }

    private Paragraph bullets(String[] lines) {
        Paragraph block = new Paragraph();
        for (String line : lines) {
            Paragraph p = new Paragraph("•  " + line, BODY);
            p.setSpacingAfter(2);
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
        p.setSpacingBefore(2);
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
