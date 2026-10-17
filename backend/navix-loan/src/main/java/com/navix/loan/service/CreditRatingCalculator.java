package com.navix.loan.service;

import com.navix.common.verification.BureauDelinquency;
import com.navix.common.verification.BureauDetail;
import com.navix.common.verification.BureauReportFacts;
import com.navix.common.verification.BureauTradeline;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Converts a bureau {@link BureauReportFacts} snapshot into a 1–5★ "should we recommend" rating, the
 * reasons behind it, and a dynamic underwriter summary. Pure / side-effect-free.
 *
 * <p>The stars follow the credit-policy BRE (the staff Customer 360 "BRE" tab), so a file the BRE
 * flags can never read as a recommendation:
 * <ol>
 *   <li><b>Base</b> from the score, anchored on the BRE rule-1 floor of {@value #BRE_MIN_SCORE}:
 *       780+ → 5.0 · 750–779 → 4.5 · 720–749 → 4.0 · 690–719 → 3.5 · 660–689 → 3.0 ·
 *       631–659 → 2.5 · 601–630 → 2.0 · ≤ 600 → 1.0</li>
 *   <li><b>Deductions</b> (BRE rules 2 and 4): worst DPD over {@value #BRE_MAX_DPD_DAYS} days in the
 *       last 12 months −1.0 · an account past due right now −0.5 · more than 3 enquiries in 30 days
 *       −0.5 · no personal loan open in the {@value #LOOKBACK_MONTHS} months to the report −0.5</li>
 *   <li><b>Caps</b>, applied last: any default → 2.5 · any written-off / settled account → 2.0 ·
 *       score at or under the BRE floor → 1.0</li>
 *   <li>clamp to [1.0, 5.0], rounded to the nearest 0.5</li>
 * </ol>
 * A fact the bureau did not send is skipped, never counted against the borrower — absent is not a
 * fail (the same rule the BRE uses for UNKNOWN).
 */
@Component
public class CreditRatingCalculator {

    private static final NumberFormat INR = NumberFormat.getInstance(new Locale("en", "IN"));

    /** BRE rule 1: the score must be more than this (frontend {@code MIN_CREDIT_SCORE}). */
    static final int BRE_MIN_SCORE = 600;
    /** BRE rule 4: no DPD over this many days (frontend {@code MAX_NBFC_DPD_DAYS}). */
    static final int BRE_MAX_DPD_DAYS = 10;
    /** BRE rule 2's personal-loan window (frontend {@code LOOKBACK_MONTHS}). */
    static final int LOOKBACK_MONTHS = 9;
    static final int MAX_RECENT_ENQUIRIES = 3;

    /** Experian Account_Type codes that are personal loans (5, 41 microfinance-personal, 45 P2P, 69 STPL). */
    private static final Set<Integer> EXPERIAN_PERSONAL_CODES = Set.of(5, 41, 45, 69);
    private static final Pattern PERSONAL_LOAN = Pattern.compile("\\bpersonal\\s+loan\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SECURED_PRODUCT = Pattern.compile(
            "auto|two.?wheeler|vehicle|housing|home|gold|property|education|business", Pattern.CASE_INSENSITIVE);

    /**
     * Recommendation stamped on a report we cannot rate. Never persisted — {@code CreditBriefService}
     * stores a null recommendation for these; it exists so the record is always well-formed.
     */
    static final String UNRATED = "SCORE UNAVAILABLE";

    /**
     * Outcome of a rating run: 1–5★, a verdict band, a 2–3 sentence underwriter summary, and the
     * reasons — the score band first, then each deduction or cap that moved the stars.
     */
    public record Rating(double stars, String recommendation, String summary, List<String> reasons) {

        public Rating(double stars, String recommendation, String summary) {
            this(stars, recommendation, summary, List.of());
        }

        /**
         * False when the bureau returned a report but no usable score.
         *
         * <p>Callers MUST NOT persist or render stars for an unrated report: {@code stars} is 0.0
         * only as a placeholder, and "0.0 / 5" reads as the worst possible borrower rather than
         * "not scored". Kept a primitive so the PDF renderer's {@code StarRowEvent(double)} and every
         * {@code %.1f} format string stay as they are.
         */
        public boolean rated() {
            return stars > 0;
        }
    }

    /** The BRE facts the stars read beyond the score; any may be null (not reported). */
    record Signals(Integer worstDpd12m, Integer accountsPastDueNow, Integer writtenOffOrSettled,
                   Boolean personalLoanInWindow) {
        static final Signals NONE = new Signals(null, null, null, null);
    }

    public Rating rate(BureauReportFacts f) {
        return rate(f, LocalDate.now());
    }

    /** @param asOf the day the report was pulled — the personal-loan window ends here. */
    public Rating rate(BureauReportFacts f, LocalDate asOf) {
        // A report with no usable score is NOT a zero-score borrower. nz() below would turn a null
        // into 0, drop it into the lowest band and stamp a fabricated 1.0★ "NOT RECOMMENDED" on a
        // file whose tradelines are perfectly real — which is exactly what CRIF sends when SCORE-VALUE
        // comes back outside 300-900. Rate nothing, summarise what we do know, and let the credit
        // team read the accounts.
        if (f.creditScore() == null) {
            return new Rating(0.0, UNRATED, summarizeUnscored(f), List.of());
        }
        int score = nz(f.creditScore());
        int defaults = nz(f.defaults());
        long total = nz(f.totalBalanceRupees());
        long secured = nz(f.securedBalanceRupees());
        long unsecured = nz(f.unsecuredBalanceRupees());
        int inquiries = f.recentInquiries30d() != null ? f.recentInquiries30d()
                : f.detail() != null && f.detail().enquiryVelocity() != null
                        ? nz(f.detail().enquiryVelocity().last30()) : 0;

        List<String> reasons = new ArrayList<>();
        double stars = computeStars(score, defaults, inquiries, signals(f, asOf), reasons);
        return new Rating(stars, verdict(stars),
                summarize(f, score, defaults, total, secured, unsecured, inquiries, stars, reasons),
                List.copyOf(reasons));
    }

    static Signals signals(BureauReportFacts f, LocalDate asOf) {
        BureauDetail d = f.detail();
        if (d == null) {
            return Signals.NONE;
        }
        BureauDelinquency q = d.delinquency();
        return new Signals(
                q != null ? q.worstDpd12m() : null,
                q != null ? q.accountsCurrentlyPastDue() : null,
                q != null ? q.accountsWrittenOffOrSettled() : null,
                personalLoanInWindow(d.tradelines(), asOf));
    }

    /** BRE rule 2: a personal loan, active or closed, in the window. Null when there are no tradelines. */
    static Boolean personalLoanInWindow(List<BureauTradeline> tradelines, LocalDate asOf) {
        if (tradelines == null || tradelines.isEmpty()) {
            return null;
        }
        String end = asOf.toString();
        String start = asOf.minusMonths(LOOKBACK_MONTHS).toString();
        for (BureauTradeline t : tradelines) {
            if (!isPersonalLoan(t.accountTypeCode())) {
                continue;
            }
            if (isIso(t.openedOn()) && t.openedOn().compareTo(end) > 0) {
                continue;
            }
            String closed = isIso(t.closedOn()) ? t.closedOn() : null;
            if (closed == null || closed.compareTo(start) >= 0) {
                return true;
            }
        }
        return false;
    }

    static boolean isPersonalLoan(String type) {
        if (type == null || type.isBlank()) {
            return false;
        }
        String t = type.trim();
        if (t.chars().allMatch(Character::isDigit)) {
            return EXPERIAN_PERSONAL_CODES.contains(Integer.parseInt(t));
        }
        return PERSONAL_LOAN.matcher(t).find() && !SECURED_PRODUCT.matcher(t).find();
    }

    /** Base band from the score alone. */
    static double baseStars(int score) {
        if (score >= 780) {
            return 5.0;
        }
        if (score >= 750) {
            return 4.5;
        }
        if (score >= 720) {
            return 4.0;
        }
        if (score >= 690) {
            return 3.5;
        }
        if (score >= 660) {
            return 3.0;
        }
        if (score > 630) {
            return 2.5;
        }
        if (score > BRE_MIN_SCORE) {
            return 2.0;
        }
        return 1.0;
    }

    /** Score-only rating (no bureau detail). Package-private so it can be unit-tested directly. */
    double computeStars(int score, int defaults, int recentInquiries) {
        return computeStars(score, defaults, recentInquiries, Signals.NONE, new ArrayList<>());
    }

    /** The rating math; appends one human-readable reason per band / deduction / cap applied. */
    double computeStars(int score, int defaults, int recentInquiries, Signals s, List<String> reasons) {
        double r = baseStars(score);
        reasons.add("Score " + score + " → " + fmt(r) + "★");

        if (s.worstDpd12m() != null && s.worstDpd12m() > BRE_MAX_DPD_DAYS) {
            r -= 1.0;
            reasons.add(s.worstDpd12m() + " days past due in the last 12 months (over "
                    + BRE_MAX_DPD_DAYS + ") −1.0");
        }
        if (s.accountsPastDueNow() != null && s.accountsPastDueNow() > 0) {
            r -= 0.5;
            reasons.add(s.accountsPastDueNow() + (s.accountsPastDueNow() == 1 ? " account" : " accounts")
                    + " overdue right now −0.5");
        }
        if (recentInquiries > MAX_RECENT_ENQUIRIES) {
            r -= 0.5;
            reasons.add(recentInquiries + " enquiries in the last 30 days −0.5");
        }
        if (Boolean.FALSE.equals(s.personalLoanInWindow())) {
            r -= 0.5;
            reasons.add("No personal loan in the last " + LOOKBACK_MONTHS + " months −0.5");
        }
        // Caps — ceilings applied last, so no deduction order can lift a file past them.
        if (defaults > 0 && r > 2.5) {
            r = 2.5;
            reasons.add(defaults + (defaults == 1 ? " default" : " defaults") + " → capped at 2.5★");
        }
        if (s.writtenOffOrSettled() != null && s.writtenOffOrSettled() > 0 && r > 2.0) {
            r = 2.0;
            reasons.add(s.writtenOffOrSettled() + " written-off / settled "
                    + (s.writtenOffOrSettled() == 1 ? "account" : "accounts") + " → capped at 2.0★");
        }
        if (score <= BRE_MIN_SCORE && r > 1.0) {
            r = 1.0;
        }
        if (score <= BRE_MIN_SCORE) {
            reasons.add("Score not above " + BRE_MIN_SCORE + " fails BRE rule 1 → capped at 1.0★");
        }
        r = Math.max(1.0, Math.min(5.0, r));
        return Math.round(r * 2.0) / 2.0;
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }

    private static boolean isIso(String s) {
        return s != null && s.length() == 10 && s.charAt(4) == '-' && s.charAt(7) == '-';
    }

    /** Verdict band shown alongside the stars. */
    String verdict(double stars) {
        if (stars >= 4.5) {
            return "STRONGLY RECOMMEND";
        }
        if (stars >= 3.5) {
            return "RECOMMEND";
        }
        if (stars >= 2.5) {
            return "REFER — MANUAL REVIEW";
        }
        return "NOT RECOMMENDED";
    }

    private String summarize(BureauReportFacts f, int score, int defaults, long total, long secured,
                             long unsecured, int inquiries, double stars, List<String> reasons) {
        String name = (f.name() != null && !f.name().isBlank()) ? titleCase(f.name()) : "The customer";
        String tier = score >= 750 ? "an excellent"
                : score >= 720 ? "a strong"
                : score >= 660 ? "a fair"
                : score > BRE_MIN_SCORE ? "a weak" : "a below-policy";

        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" presents ").append(tier).append(" credit profile (bureau score ")
                .append(score).append(") with ").append(defaults)
                .append(defaults == 1 ? " reported default" : " reported defaults");
        if (f.totalAccounts() != null) {
            sb.append(" across ").append(f.totalAccounts())
                    .append(f.totalAccounts() == 1 ? " account" : " accounts");
            if (f.activeAccounts() != null) {
                sb.append(" (").append(f.activeAccounts()).append(" active)");
            }
        }
        sb.append(". ");

        sb.append("Total outstanding exposure is ").append(inr(total));
        if (total > 0) {
            long securedPct = Math.round(secured * 100.0 / total);
            sb.append(secured >= unsecured
                    ? ", predominantly secured (" + securedPct + "%) — a healthy debt mix"
                    : ", unsecured-heavy (" + (100 - securedPct) + "% unsecured) — a higher-risk mix");
        }
        sb.append(". ");

        if (inquiries > 3) {
            sb.append(inquiries).append(" enquiries in the last 30 days indicate recent credit-seeking; ");
        } else {
            sb.append("Recent enquiry activity is low; ");
        }
        sb.append(verdictSentence(stars)).append(".");
        if (reasons.size() > 1) {
            sb.append(" Rating drivers: ").append(String.join("; ", reasons.subList(1, reasons.size())))
                    .append(".");
        }
        return sb.toString();
    }

    /**
     * The summary for a report we cannot rate. Deliberately mentions neither a score nor a verdict:
     * {@link #summarize} hardcodes "(bureau score N)" and closes with a recommendation sentence, and
     * with a null score both would be fiction — "a weak credit profile (bureau score 0)" for a
     * borrower whose file we simply could not score.
     */
    private String summarizeUnscored(BureauReportFacts f) {
        String name = (f.name() != null && !f.name().isBlank()) ? titleCase(f.name()) : "The customer";
        int defaults = nz(f.defaults());
        StringBuilder sb = new StringBuilder();
        sb.append(name).append(" has a bureau record, but the bureau returned no usable credit score");
        if (f.totalAccounts() != null) {
            sb.append(". The report covers ").append(f.totalAccounts())
                    .append(f.totalAccounts() == 1 ? " account" : " accounts");
            if (f.activeAccounts() != null) {
                sb.append(" (").append(f.activeAccounts()).append(" active)");
            }
        }
        sb.append(". Total outstanding exposure is ").append(inr(nz(f.totalBalanceRupees())))
                .append(" with ").append(defaults)
                .append(defaults == 1 ? " reported default" : " reported defaults")
                .append(". Underwrite from the account history rather than a score.");
        return sb.toString();
    }

    private String verdictSentence(double stars) {
        if (stars >= 4.5) {
            return "strongly recommended for approval";
        }
        if (stars >= 3.5) {
            return "recommended subject to standard verification";
        }
        if (stars >= 2.5) {
            return "refer for manual underwriting review";
        }
        return "not recommended at this time";
    }

    /** ₹ with Indian digit grouping, e.g. 861232 → "₹8,61,232". */
    static String inr(long rupees) {
        synchronized (INR) {
            return "₹" + INR.format(rupees);
        }
    }

    private static String titleCase(String s) {
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

    private static int nz(Integer v) {
        return v != null ? v : 0;
    }

    private static long nz(Long v) {
        return v != null ? v : 0L;
    }
}
