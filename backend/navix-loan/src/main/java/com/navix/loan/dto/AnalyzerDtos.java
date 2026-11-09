package com.navix.loan.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Staff-only "AI Analyzer" (a rule-based summary of past loans, advisory only). Money is integer
 * paise; every rate is derived from the counts here by the UI, never shipped as a float.
 */
public final class AnalyzerDtos {

    private AnalyzerDtos() {
    }

    /**
     * One sign. {@code state}: first-loan pointers CONCERN / OK / UNKNOWN; informational ones
     * YES / NO / UNKNOWN; null inside {@link Benchmarks}. {@code value} is the raw figure behind the
     * state (accounts, days, paise); null when unknown or not numeric.
     */
    public record Pointer(String key, String state, Long value,
                          int withLoans, int withBad, int withoutLoans, int withoutBad) {
    }

    public record CustomerFacts(Long salaryPaise, Integer totalAccounts, Boolean employedAtDeclared,
                                Integer score, BigDecimal stars, String recommendation, String riskCategory) {
    }

    public record OwnRecord(int loansTaken, int resolved, int good, int bad, int onTime, int late1to5,
                            int openLoans, Integer maxOpenDpd, int zeroPaidBad, Integer lastClosedDaysEarly,
                            long totalPrincipalPaise, long totalCollectedPaise) {
    }

    /** key: ALL, EARLY_FIRST, RECENT_FIRST, REPEAT. */
    public record GroupStats(String key, int loans, int customers, int bad, long principalPaise,
                             long badPrincipalPaise, long paidOutPaise, long collectedPaise, int zeroPaidBad) {
    }

    public record PatternCount(String pattern, int customers) {
    }

    public record PointRow(int points, int loans, int bad, int zeroPaid) {
    }

    public record BandRow(String band, int loans, int bad) {
    }

    public record IncomeStats(int loans, int bad, int zeroPaid) {
    }

    public record RepeatStats(int earlyLoans, int earlyBad, int otherLoans, int otherBad) {
    }

    public record Benchmarks(String recentStart, GroupStats all, GroupStats earlyFirst, GroupStats recentFirst,
                             GroupStats repeat, Integer breakEvenBps, List<PatternCount> patterns,
                             List<Pointer> concerns, List<Pointer> informational, List<PointRow> pointTable,
                             List<BandRow> bands, IncomeStats incomeUncorroborated, RepeatStats repeatEarlyPayer) {
    }

    /** mode: OWN_RECORD / FIRST_LOAN / NO_DATA. band: LOWER / WATCH / HIGH; null for NO_DATA. */
    public record AnalyzerView(String asOf, String mode, String band, int concernCount, int unknownCount,
                               List<Pointer> pointers, List<Pointer> informational, CustomerFacts facts,
                               OwnRecord ownRecord, boolean incomeUncorroborated, Benchmarks benchmarks) {
    }
}
