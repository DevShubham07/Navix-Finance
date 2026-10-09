package com.navix.app.dashboard;

import com.navix.app.dashboard.DashboardDtos.Factor;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

/**
 * Leaderboard scoring, pure so it can be unit-tested without a database.
 *
 * <p>{@code score = 10 * sum(w*f) / sum(w)} over the factors that are NOT null, each f clamped to
 * 0..1, rounded to two decimals. A factor with no denominator is null ("cannot measure") and drops out
 * of the weighting rather than counting as zero; when every factor is null the score is null and the
 * row sorts last.
 */
public final class ScoreFormulas {

    private ScoreFormulas() {
    }

    /** One factor before it is rendered: a null {@code value} means "cannot be measured". */
    public record Input(String key, String label, Double value, double weight) {
    }

    /** A leaderboard entry before ranking. */
    public record Ranked(long staffId, String name, Double score, long volume) {
    }

    // Credit: repayment quality dominates, because approval rate alone rewards approving everything.
    static final double W_PROCESSED = 0.3;
    static final double W_APPROVAL = 0.2;
    static final double W_EFFICIENCY = 0.5;
    // Collection: money recovered matters most; volume stops one easy case topping the board.
    static final double W_RECOVERY = 0.5;
    static final double W_CLOSED = 0.3;
    static final double W_COLLECTION_VOLUME = 0.2;
    // Telecaller
    static final double W_CONVERSION = 0.5;
    static final double W_CONTACT = 0.3;
    static final double W_CALL_VOLUME = 0.2;

    public static Double ratio(long numerator, long denominator) {
        return denominator <= 0 ? null : (double) numerator / denominator;
    }

    static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    public static Double score(List<Input> factors) {
        double weighted = 0;
        double weights = 0;
        for (Input f : factors) {
            if (f.value() == null) continue;
            weighted += f.weight() * clamp01(f.value());
            weights += f.weight();
        }
        if (weights == 0) return null;
        return BigDecimal.valueOf(10 * weighted / weights).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    /** Stars out of 5 for a 0..10 score. */
    public static Integer stars(Double score) {
        return score == null ? null : (int) Math.round(score / 2.0);
    }

    /** Renders inputs for the wire: percentage display and stars out of 5 per factor. */
    public static List<Factor> factors(List<Input> inputs) {
        return inputs.stream().map(i -> new Factor(i.key(), i.label(), i.value(),
                i.value() == null ? "—" : Math.round(clamp01(i.value()) * 100) + "%", i.weight(),
                i.value() == null ? null : (int) Math.round(clamp01(i.value()) * 5))).toList();
    }

    /** Highest score first (null last), then higher volume, then name. */
    public static final Comparator<Ranked> ORDER = Comparator
            .comparing(Ranked::score, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(Ranked::volume, Comparator.reverseOrder())
            .thenComparing(Ranked::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));

    public static List<Ranked> rank(List<Ranked> entries) {
        return entries.stream().sorted(ORDER).toList();
    }

    // ---- factor builders ----------------------------------------------------------------

    public static List<Input> credit(long assigned, long sanctioned, long rejected, long dueRepayable,
                                     long dueCollected) {
        return List.of(
                new Input("processed", "Processed", ratio(sanctioned + rejected, assigned), W_PROCESSED),
                new Input("approval", "Approval", ratio(sanctioned, sanctioned + rejected), W_APPROVAL),
                new Input("efficiency", "Collection efficiency", ratio(dueCollected, dueRepayable), W_EFFICIENCY));
    }

    public static List<Input> collection(long collected, long owedNow, long assigned, long closedCases,
                                         long maxAssigned) {
        return List.of(
                new Input("recovery", "Recovery", ratio(collected, collected + owedNow), W_RECOVERY),
                new Input("closed", "Cases closed", ratio(closedCases, assigned), W_CLOSED),
                new Input("volume", "Volume", ratio(assigned, maxAssigned), W_COLLECTION_VOLUME));
    }

    public static List<Input> telecaller(long leads, long converted, long contacted, long calls, long maxCalls) {
        return List.of(
                new Input("conversion", "Conversion", ratio(converted, leads), W_CONVERSION),
                new Input("contact", "Contact rate", ratio(contacted, leads), W_CONTACT),
                new Input("volume", "Call volume", ratio(calls, maxCalls), W_CALL_VOLUME));
    }
}
