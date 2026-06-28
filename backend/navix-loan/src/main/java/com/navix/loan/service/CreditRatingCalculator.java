package com.navix.loan.service;

import com.navix.common.verification.BureauReportFacts;
import java.text.NumberFormat;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Converts a bureau {@link BureauReportFacts} snapshot into a 1–5★ "should we recommend" rating plus a
 * dynamic underwriter summary. Pure / side-effect-free.
 *
 * <p>The star logic mirrors the product spec's {@code calculate_risk_rating(score, defaults,
 * total_balance, unsecured_balance)} — re-expressed in Java and given the {@code recentInquiries(30d)}
 * input that Penalty&nbsp;2 needs (the original signature omitted it). Applied order:
 * <ol>
 *   <li><b>Base</b> from the score band: &gt;750 → 4.5 · 700–750 → 4.0 · 650–699 → 3.0 · &lt;650 → 2.0</li>
 *   <li><b>Bonus</b>: score &gt; 770 and 0 defaults → 4.5; and if total balance is 0 → 5.0</li>
 *   <li><b>Penalty 2</b>: &gt; 3 recent enquiries → −0.5</li>
 *   <li><b>Penalty 1</b>: any default → hard ceiling min(rating, 2.5), applied last</li>
 *   <li>clamp to [1.0, 5.0], rounded to the nearest 0.5</li>
 * </ol>
 */
@Component
public class CreditRatingCalculator {

    private static final NumberFormat INR = NumberFormat.getInstance(new Locale("en", "IN"));

    /** Outcome of a rating run: 1–5★, a verdict band, and a 2–3 sentence underwriter summary. */
    public record Rating(double stars, String recommendation, String summary) {
    }

    public Rating rate(BureauReportFacts f) {
        int score = nz(f.creditScore());
        int defaults = nz(f.defaults());
        long total = nz(f.totalBalanceRupees());
        long secured = nz(f.securedBalanceRupees());
        long unsecured = nz(f.unsecuredBalanceRupees());
        int inquiries = nz(f.recentInquiries30d());

        double stars = computeStars(score, defaults, total, inquiries);
        return new Rating(stars, verdict(stars),
                summarize(f, score, defaults, total, secured, unsecured, inquiries, stars));
    }

    /** The spec's rating math. Package-private so it can be unit-tested directly. */
    double computeStars(int score, int defaults, long totalBalance, int recentInquiries) {
        double r;
        if (score > 750) {
            r = 4.5;
        } else if (score >= 700) {
            r = 4.0;
        } else if (score >= 650) {
            r = 3.0;
        } else {
            r = 2.0;
        }
        // Bonus — strong, clean file. (Mutually exclusive with the defaults cap below.)
        if (score > 770 && defaults == 0) {
            r = 4.5;
            if (totalBalance == 0) {
                r = 5.0;
            }
        }
        // Penalty 2 — recent credit-seeking.
        if (recentInquiries > 3) {
            r -= 0.5;
        }
        // Penalty 1 — any default is a hard ceiling, applied last.
        if (defaults > 0) {
            r = Math.min(r, 2.5);
        }
        r = Math.max(1.0, Math.min(5.0, r));
        return Math.round(r * 2.0) / 2.0;
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
                             long unsecured, int inquiries, double stars) {
        String name = (f.name() != null && !f.name().isBlank()) ? titleCase(f.name()) : "The applicant";
        String tier = score >= 750 ? "an excellent"
                : score >= 700 ? "a strong"
                : score >= 650 ? "a fair" : "a weak";

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
