package com.navix.loan.pdf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.navix.common.verification.BureauDelinquency;
import com.navix.common.verification.BureauDetail;
import com.navix.common.verification.BureauEnquiry;
import com.navix.common.verification.BureauEnquiryVelocity;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauTradeline;
import com.navix.loan.service.CreditRatingCalculator.Rating;
import com.navix.common.verification.BureauCodes;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Renders the one-page, DhanBoost-branded credit brief to PDF bytes (OpenPDF). Pure / stateless.
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
    private static final Color ERROR = new Color(178, 34, 34);

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
    private static final Font REPORT_HEADER = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);
    private static final Font REPORT_PATH = new Font(Font.HELVETICA, 6.5f, Font.NORMAL, GREY);
    private static final Font REPORT_VALUE = new Font(Font.HELVETICA, 7f, Font.NORMAL, new Color(33, 43, 54));
    /** A currently-past-due amount in the tradeline table — same emphasis the staff UI gives it. */
    private static final Font REPORT_VALUE_EMPHASIS = new Font(Font.HELVETICA, 7f, Font.BOLD, ERROR);

    private static final NumberFormat IN = NumberFormat.getInstance(new Locale("en", "IN"));
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Render the brief. {@code generatedOn} is supplied by the caller (keeps the renderer pure). */
    public byte[] render(long applicationId, Long customerId, String bureauSource,
                         BureauReportFacts f, Rating rating, LocalDate generatedOn) {
        return render(applicationId, customerId, bureauSource, f, rating, generatedOn, null);
    }

    /** Render the summary followed by every scalar field in the exact provider response. */
    public byte[] render(long applicationId, Long customerId, String bureauSource,
                         BureauReportFacts f, Rating rating, LocalDate generatedOn,
                         String rawResponseJson) {
        // Landscape A4 (842×595pt): the fact tables (3-up categories, 2-col provider report) were
        // sized for the narrower 595pt portrait page and read as cramped; the wider page gives each
        // column meaningfully more room without shrinking type.
        Document doc = new Document(PageSize.A4.rotate(), 42, 42, 40, 40);
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

            // `detail` is null on any brief generated before tradeline parsing shipped (no backfill
            // ran) — an older/thin-file brief must render exactly as it did before this change, so
            // this whole block is skippable rather than an empty heading + table.
            if (f.detail() != null) {
                addDetailSections(doc, f.detail());
            }

            PdfPTable providerReport = providerReportTable(rawResponseJson);
            if (providerReport != null) {
                doc.add(spaced(new Paragraph("Complete Provider Response", SECTION), 12, 3));
                doc.add(new Paragraph(
                        "Every field received from the bureau provider is listed below. Array positions are one-based.",
                        META));
                doc.add(providerReport);
            }

            doc.add(footerSpacer());
            doc.add(rule());
            Paragraph footer = new Paragraph(
                    "Confidential — DhanBoost internal underwriting brief.  Generated " + generatedOn
                            + ".  Bureau data is provided as-is for credit decisioning.", FOOTER);
            footer.setAlignment(Element.ALIGN_CENTER);
            doc.add(footer);

            doc.close();
            return out.toByteArray();
        } catch (DocumentException e) {
            throw new IllegalStateException("Failed to render credit-brief PDF", e);
        }
    }

    /**
     * Hard bound on the raw appendix below. This section flattens the ENTIRE provider response into
     * one row per leaf field, and bureau reports are enormous: the 196 KB sample renders 91 pages,
     * and the largest real production response (2.0 MB, 543 tradelines) would render roughly 930 —
     * a credit brief nobody can open, held in memory by OpenPDF and pushed to S3 on every pull.
     *
     * <p>The appendix existed because it was the only route by which account-level detail reached
     * staff at all. It no longer is: the structured tradeline, enquiry and delinquency sections above
     * carry the parts that inform a decision, and the complete untruncated response remains available
     * in the staff console and the provider-API dashboard. So this keeps the appendix useful as a
     * spot-check while refusing to let one borrower's file become a thousand-page document.
     */
    private static final int MAX_PROVIDER_FIELDS = 400;

    private PdfPTable providerReportTable(String rawResponseJson) throws DocumentException {
        if (rawResponseJson == null || rawResponseJson.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = JSON.readTree(rawResponseJson);
        } catch (Exception invalidJson) {
            throw new IllegalArgumentException("Invalid provider response JSON", invalidJson);
        }
        List<ReportField> fields = new ArrayList<>();
        flatten(root, "", fields);
        int total = fields.size();
        boolean truncated = total > MAX_PROVIDER_FIELDS;
        if (truncated) {
            fields = fields.subList(0, MAX_PROVIDER_FIELDS);
        }

        PdfPTable table = new PdfPTable(2);
        table.setWidthPercentage(100);
        table.setWidths(new float[] {2.2f, 1.8f});
        table.setSpacingBefore(5f);
        table.setSplitLate(false);
        table.setHeaderRows(1);
        table.addCell(reportHeader("Provider field path"));
        table.addCell(reportHeader("Provider value"));
        for (ReportField field : fields) {
            table.addCell(reportCell(field.path(), REPORT_PATH));
            table.addCell(reportCell(field.value(), REPORT_VALUE));
        }
        if (truncated) {
            table.addCell(reportCell("… " + (total - MAX_PROVIDER_FIELDS) + " further fields not printed", REPORT_PATH));
            table.addCell(reportCell("Open the full provider response in the staff console", REPORT_VALUE));
        }
        return table;
    }

    private static void flatten(JsonNode node, String path, List<ReportField> out) {
        if (node == null || node.isNull()) {
            out.add(new ReportField(nonBlankPath(path), "null"));
            return;
        }
        if (node.isObject()) {
            if (node.isEmpty()) {
                out.add(new ReportField(nonBlankPath(path), "{}"));
                return;
            }
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                flatten(field.getValue(), appendPath(path, humanize(field.getKey())), out);
            }
            return;
        }
        if (node.isArray()) {
            if (node.isEmpty()) {
                out.add(new ReportField(nonBlankPath(path), "[]"));
                return;
            }
            for (int i = 0; i < node.size(); i++) {
                flatten(node.get(i), nonBlankPath(path) + " [" + (i + 1) + "]", out);
            }
            return;
        }
        String value = node.isTextual() && node.asText().isEmpty() ? "\"\"" : node.asText();
        out.add(new ReportField(nonBlankPath(path), pdfSafe(value)));
    }

    private static String appendPath(String path, String part) {
        return path == null || path.isBlank() ? part : path + " > " + part;
    }

    private static String nonBlankPath(String path) {
        return path == null || path.isBlank() ? "Response" : path;
    }

    private static String humanize(String key) {
        String spaced = key.replace('_', ' ').replace('-', ' ')
                .replaceAll("([a-z0-9])([A-Z])", "$1 $2")
                .replaceAll("\\s+", " ").trim();
        StringBuilder result = new StringBuilder(spaced.length());
        for (String part : spaced.split(" ")) {
            if (part.isEmpty()) continue;
            if (result.length() > 0) result.append(' ');
            result.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return result.toString();
    }

    private static PdfPCell reportHeader(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, REPORT_HEADER));
        cell.setBackgroundColor(NAVY);
        cell.setPadding(5f);
        return cell;
    }

    private static PdfPCell reportCell(String text, Font font) {
        PdfPCell cell = new PdfPCell(new Phrase(pdfSafe(text), font));
        cell.setBorderColor(LINE);
        cell.setPadding(4f);
        return cell;
    }

    private record ReportField(String path, String value) {
    }

    // -----------------------------------------------------------------------------------------
    // Structured detail sections (delinquency / enquiry velocity / tradelines / enquiries) —
    // the interpreted "missing middle" between the twelve `categories()` scalars above and the
    // raw `providerReportTable` appendix below. Mirrors the staff UI's
    // credit/tradeline-table.tsx (TradelineTable/EnquiryTable/DelinquencySummaryBlock/
    // EnquiryVelocityBlock) field-for-field and rule-for-rule so the PDF a reviewer downloads
    // can't disagree with the screen they read it off. See that file's per-rule comments for the
    // production-data reasoning (900+ DPD, null-vs-zero, negative balances, etc.) — the same
    // rules are re-stated locally below since Java/TS can't share the helper.
    // -----------------------------------------------------------------------------------------

    private void addDetailSections(Document doc, BureauDetail detail) throws DocumentException {
        doc.add(spaced(new Paragraph("Delinquency History", SECTION), 12, 3));
        BureauDelinquency d = detail.delinquency();
        if (d != null) {
            doc.add(delinquencyTable(d));
        } else {
            doc.add(new Paragraph("No delinquency aggregates parsed for this report.", META));
        }

        doc.add(spaced(new Paragraph("Enquiry Velocity", SECTION), 12, 3));
        BureauEnquiryVelocity v = detail.enquiryVelocity();
        if (v != null) {
            doc.add(enquiryVelocityTable(v));
        } else {
            doc.add(new Paragraph("No enquiry-velocity data parsed for this report.", META));
        }

        doc.add(spaced(new Paragraph("Tradelines", SECTION), 12, 3));
        List<BureauTradeline> tradelines = detail.tradelines() != null ? detail.tradelines() : List.of();
        if (tradelines.isEmpty()) {
            doc.add(new Paragraph("No tradelines parsed for this report.", META));
        } else {
            // Bound the table: the largest real report has 543 tradelines, and printing every one
            // would run this brief to hundreds of pages. Print only the rows that matter for a
            // credit decision — open, delinquent, written-off, restructured, or currently past
            // due (the same `isDefaultVisible` cut the staff UI's "Show all" toggle defaults to) —
            // worst-first, and say plainly how many of the true total that is rather than silently
            // truncating.
            List<BureauTradeline> visible = tradelines.stream()
                    .filter(CreditBriefPdfRenderer::isTradelineDefaultVisible)
                    .sorted(tradelineComparator())
                    .toList();
            int total = detail.tradelineCount() != null ? detail.tradelineCount() : tradelines.size();
            String caption = visible.size() < total
                    ? "Showing " + visible.size() + " of " + total
                            + " tradelines in the report (open, delinquent, written-off, restructured, or currently past due)."
                    : visible.size() + " tradeline" + (visible.size() == 1 ? "" : "s") + ".";
            doc.add(new Paragraph(caption, META));
            if (visible.isEmpty()) {
                doc.add(new Paragraph("No open, delinquent, written-off, restructured or past-due tradelines.", META));
            } else {
                doc.add(tradelineTable(visible));
            }
        }

        doc.add(spaced(new Paragraph("Enquiries", SECTION), 12, 3));
        List<BureauEnquiry> enquiries = detail.enquiries() != null ? detail.enquiries() : List.of();
        if (enquiries.isEmpty()) {
            doc.add(new Paragraph("No enquiries parsed for this report.", META));
        } else {
            doc.add(enquiryTable(enquiries));
        }
    }

    private PdfPTable delinquencyTable(BureauDelinquency d) throws DocumentException {
        // Every field here is `Integer`/`String` that may be null — null means the bureau data
        // couldn't tell us and MUST print "—", never "0". A fabricated "0 accounts ever 30+ dpd"
        // on a report where the bureau simply didn't say is how a delinquent borrower gets waved
        // through underwriting.
        return kvTable(new String[][] {
                {"Worst DPD (12m)", formatDpdDays(d.worstDpd12m())},
                {"Worst DPD (24m)", formatDpdDays(d.worstDpd24m())},
                {"Worst DPD (36m)", formatDpdDays(d.worstDpd36m())},
                {"Accounts ever 30+ dpd", formatCount(d.accountsEver30Plus())},
                {"Currently past due", formatCount(d.accountsCurrentlyPastDue())},
                {"Written-off / settled", formatCount(d.accountsWrittenOffOrSettled())},
                {"Oldest account opened", safe(d.oldestAccountOpenedOn(), "—")},
        });
    }

    private PdfPTable enquiryVelocityTable(BureauEnquiryVelocity v) throws DocumentException {
        return kvTable(new String[][] {
                {"Last 7 days", formatCount(v.last7())},
                {"Last 30 days", formatCount(v.last30())},
                {"Last 90 days", formatCount(v.last90())},
                {"Last 180 days", formatCount(v.last180())},
        });
    }

    /** Plain label|value rows, one per line — used for the delinquency/velocity blocks above. */
    private PdfPTable kvTable(String[][] rows) throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(60);
        t.setHorizontalAlignment(Element.ALIGN_LEFT);
        t.setWidths(new float[] {1.4f, 1f});
        t.setSpacingBefore(2f);
        t.setSpacingAfter(4f);
        for (String[] row : rows) {
            kv(t, row[0], row[1]);
        }
        return t;
    }

    private static void kv(PdfPTable t, String label, String value) {
        t.addCell(kvCell(new Phrase(label, LABEL)));
        t.addCell(kvCell(new Phrase(value, VALUE)));
    }

    // ---- Tradeline table ----

    /** Rows that matter for a credit decision by default — mirrors the staff UI's
     *  `isDefaultVisible` in credit/tradeline-table.tsx exactly. */
    private static boolean isTradelineDefaultVisible(BureauTradeline t) {
        Long pastDue = t.amountPastDueRupees();
        if (pastDue != null && pastDue > 0) {
            return true;
        }
        StatusBucket bucket = classifyStatus(t.accountStatusCode());
        return bucket != StatusBucket.CLOSED && bucket != StatusBucket.SETTLED;
    }

    /** Worst-first, deterministic — mirrors the staff UI's `tradelinePriority`/`sortTradelines`. */
    private static int tradelinePriority(BureauTradeline t) {
        StatusBucket bucket = classifyStatus(t.accountStatusCode());
        if (bucket == StatusBucket.DELINQUENT || bucket == StatusBucket.WRITTEN_OFF) {
            return 0;
        }
        Long pastDue = t.amountPastDueRupees();
        if (pastDue != null && pastDue > 0) {
            return 1;
        }
        if (bucket == StatusBucket.ACTIVE) {
            return 2;
        }
        return 3;
    }

    private static Comparator<BureauTradeline> tradelineComparator() {
        return Comparator.comparingInt(CreditBriefPdfRenderer::tradelinePriority)
                .thenComparing((BureauTradeline t) -> nz(t.currentBalanceRupees()), Comparator.reverseOrder())
                .thenComparing((BureauTradeline t) -> t.closedOn() == null ? "" : t.closedOn(),
                        Comparator.reverseOrder());
    }

    private PdfPTable tradelineTable(List<BureauTradeline> rows) throws DocumentException {
        PdfPTable t = new PdfPTable(7);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {1.6f, 1.1f, 0.9f, 0.8f, 0.9f, 0.9f, 0.9f});
        t.setSpacingBefore(3f);
        t.setSpacingAfter(6f);
        t.setHeaderRows(1);
        for (String h : new String[] {"Lender", "Type", "Status", "Opened", "Balance", "Past due", "Worst DPD"}) {
            t.addCell(reportHeader(h));
        }
        for (BureauTradeline tl : rows) {
            boolean pastDue = tl.amountPastDueRupees() != null && tl.amountPastDueRupees() > 0;
            t.addCell(reportCell(safe(tl.lender(), "—"), REPORT_VALUE));
            t.addCell(reportCell(accountTypeLabel(tl.accountTypeCode()), REPORT_VALUE));
            t.addCell(reportCell(accountStatusLabel(tl.accountStatusCode()), REPORT_VALUE));
            t.addCell(reportCell(safe(tl.openedOn(), "—"), REPORT_VALUE));
            t.addCell(reportCell(rs(tl.currentBalanceRupees()), REPORT_VALUE));
            t.addCell(reportCell(rs(tl.amountPastDueRupees()), pastDue ? REPORT_VALUE_EMPHASIS : REPORT_VALUE));
            t.addCell(reportCell(formatDpdDays(tl.worstDpdMonths()), REPORT_VALUE));
        }
        return t;
    }

    // ---- Enquiry table ----

    private PdfPTable enquiryTable(List<BureauEnquiry> rows) throws DocumentException {
        // Sorted newest-first, mirrors the staff UI's `sortEnquiries`.
        List<BureauEnquiry> sorted = rows.stream()
                .sorted(Comparator.comparing((BureauEnquiry e) -> e.requestedOn() == null ? "" : e.requestedOn())
                        .reversed())
                .toList();
        PdfPTable t = new PdfPTable(5);
        t.setWidthPercentage(100);
        t.setWidths(new float[] {0.9f, 1.6f, 1.3f, 1f, 0.8f});
        t.setSpacingBefore(3f);
        t.setSpacingAfter(4f);
        t.setHeaderRows(1);
        for (String h : new String[] {"Date", "Lender", "Reason", "Amount financed", "Duration"}) {
            t.addCell(reportHeader(h));
        }
        for (BureauEnquiry e : sorted) {
            t.addCell(reportCell(safe(e.requestedOn(), "—"), REPORT_VALUE));
            t.addCell(reportCell(safe(e.subscriber(), "—"), REPORT_VALUE));
            t.addCell(reportCell(enquiryReasonLabel(e.reasonCode()), REPORT_VALUE));
            t.addCell(reportCell(rs(e.amountFinancedRupees()), REPORT_VALUE));
            t.addCell(reportCell(e.durationMonths() != null ? e.durationMonths() + " mo" : "—", REPORT_VALUE));
        }
        return t;
    }

    // ---- Bureau code -> label lookups (via BureauCodes; unknown codes never guessed) ----

    private enum StatusBucket { DELINQUENT, WRITTEN_OFF, SETTLED, RESTRUCTURED, ACTIVE, CLOSED, UNKNOWN }

    private static final Set<Integer> DELINQUENT_CODES = Set.of(71, 78, 80, 82, 84, 97);
    private static final Set<Integer> WRITTEN_OFF_CODES = Set.of(43, 45);
    private static final Set<Integer> SETTLED_CODES = Set.of(32);
    private static final Set<Integer> RESTRUCTURED_CODES = Set.of(30);
    private static final Set<Integer> ACTIVE_CODES = Set.of(11, 21);
    private static final Set<Integer> CLOSED_CODES = Set.of(13, 15);

    /** Buckets a status code for sort order/priority only — mirrors `classifyStatus` in
     *  credit/tradeline-table.tsx. The DISPLAYED label always comes from {@link BureauCodes}
     *  separately, never from this bucket. */
    private static StatusBucket classifyStatus(String code) {
        Integer n = codeToInt(code);
        if (n == null) {
            return StatusBucket.UNKNOWN;
        }
        if (DELINQUENT_CODES.contains(n)) return StatusBucket.DELINQUENT;
        if (WRITTEN_OFF_CODES.contains(n)) return StatusBucket.WRITTEN_OFF;
        if (SETTLED_CODES.contains(n)) return StatusBucket.SETTLED;
        if (RESTRUCTURED_CODES.contains(n)) return StatusBucket.RESTRUCTURED;
        if (ACTIVE_CODES.contains(n)) return StatusBucket.ACTIVE;
        if (CLOSED_CODES.contains(n)) return StatusBucket.CLOSED;
        return StatusBucket.UNKNOWN;
    }

    /** Codes arrive zero-padded ("05", "01") in real data — strip leading zeros before lookup/bucketing. */
    private static Integer codeToInt(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException notNumeric) {
            return null;
        }
    }

    /** Falls back to "Type &lt;code&gt;" for anything {@link BureauCodes} doesn't recognise —
     *  never guesses a label. */
    private static String accountTypeLabel(String code) {
        if (code == null || code.isBlank()) {
            return "—";
        }
        return BureauCodes.accountType(code).orElseGet(() -> "Type " + fallbackCode(code));
    }

    /** Falls back to "Status &lt;code&gt;" for anything {@link BureauCodes} doesn't recognise —
     *  never guesses a label. */
    private static String accountStatusLabel(String code) {
        if (code == null || code.isBlank()) {
            return "—";
        }
        return BureauCodes.accountStatus(code).orElseGet(() -> "Status " + fallbackCode(code));
    }

    private static String enquiryReasonLabel(String code) {
        if (code == null || code.isBlank()) {
            return "—";
        }
        return BureauCodes.enquiryReason(code).orElseGet(() -> "Reason " + fallbackCode(code));
    }

    /** The code to show in an unmapped-code fallback label: zero-stripped when numeric, raw otherwise. */
    private static String fallbackCode(String code) {
        Integer n = codeToInt(code);
        return n != null ? String.valueOf(n) : code;
    }

    // ---- DPD / count formatting (null vs zero matters — see the field-level javadoc on
    //      BureauDelinquency/BureauTradeline for why) ----

    /**
     * Absent ≠ zero. A `null` aggregate must render as "—", never "0" — mirrors `formatCount` in
     * credit/tradeline-table.tsx. "0 accounts past due" on a file where the bureau simply didn't
     * say is how a delinquent borrower gets waved through.
     */
    private static String formatCount(Integer n) {
        return n == null ? "—" : String.valueOf(n);
    }

    /**
     * DPD/default-age readings run to 900+ in real data (900 occurs 981 times — a genuine
     * long-default signal, not a sentinel to hide) — mirrors `formatDpdDays` in
     * credit/tradeline-table.tsx. Render "900+ days" so it reads as "very delinquent" rather than
     * looking like a data error, but never suppress it.
     */
    private static String formatDpdDays(Integer days) {
        if (days == null) {
            return "—";
        }
        if (days >= 900) {
            return "900+ days";
        }
        return days + (days == 1 ? " day" : " days");
    }

    private PdfPTable headerTable() throws DocumentException {
        PdfPTable t = new PdfPTable(2);
        t.setWidthPercentage(100);
        // Wider on the landscape page than the portrait 2:1 split — the wordmark side otherwise leaves
        // a lot of dead space next to a right-aligned title on an 842pt-wide band.
        t.setWidths(new float[] {3f, 1f});
        t.addCell(borderless(new Phrase("DhanBoost FINANCE", WORDMARK), Element.ALIGN_LEFT));
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
        // Evened out from the portrait {1.1, 1, 1.2} split: on the wider landscape page each column
        // has enough room that Exposure (the longest values — rupee amounts + percentages) no longer
        // needs an outsized share to avoid wrapping.
        t.setWidths(new float[] {1f, 1f, 1.05f});
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
