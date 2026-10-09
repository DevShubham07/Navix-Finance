package com.navix.app.dashboard;

import com.navix.app.dashboard.MetricSql.Metric;
import java.util.List;
import java.util.Map;

/**
 * Wire shapes of {@code /api/dashboard/*}. All money is {@code long} paise. A ratio is a
 * {@code Double} in 0..1, or {@code null} when it has no denominator -- null means "cannot be
 * measured", never zero. Field names are the binding contract with the frontend.
 */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    // ---- shared ---------------------------------------------------------------------------

    public record Kpi(Metric metric, long count, Long amountPaise, long fresh, long reloan,
                      Long freshPaise, Long reloanPaise, Long previousCount, Long previousAmountPaise) {
    }

    public record RateRow(double ratePct, long newCount, long repeatCount, long totalCases,
                          long principalPaise, long netDisbursedPaise, long totalRepayablePaise) {
    }

    public record Factor(String key, String label, Double value, String display, double weight, Integer stars) {
    }

    // ---- snapshot -------------------------------------------------------------------------

    public record Financial(Kpi totalLoan, Kpi pendingSanctioned, Kpi pendingDisbursal,
                            Long averageLoanPaise, Long averageFreshPaise, Long averageReloanPaise) {
    }

    public record Kpis(Kpi applications, Kpi disbursed, Kpi pending, Kpi rejected) {
    }

    public record ClosedBlock(long closedCount, long settledCount, long partPaidCount, Double closedPctOfDisbursed,
                              long collectedClosedPaise, long collectedSettledPaise, long collectedPartPaise,
                              long totalCollectedPaise) {
    }

    public record Rates(Double disbursementRate, Double pendingRate, Double rejectionRate) {
    }

    public record Snapshot(String from, String to, Kpis kpis, Financial financial, ClosedBlock closed, Rates rates,
                           List<RateRow> pfTable, List<RateRow> roiTable) {
    }

    // ---- monthly --------------------------------------------------------------------------

    public record MonthRow(String month, long dueLoans, long totalRepayablePaise, long collectedPaise,
                           Double collectionPct, long preclosedCount, Double preclosurePct, int targetBp,
                           Double deficitPct, long targetAmountPaise, long deficitAmountPaise, long disbursedPaise,
                           Long disbursalTargetPaise, Double achievedPct) {
    }

    public record MarketingRow(String month, long leads, long converted, Double conversionPct) {
    }

    public record Retention(long due, long closed, long reloan, long noRepeat, Double retentionPct) {
    }

    public record Monthly(List<MonthRow> months, List<MarketingRow> marketing, Retention retention) {
    }

    // ---- daily ----------------------------------------------------------------------------

    public record DayRow(String date, long pfPaise, long interestPaise, long penaltyPaise, long cumulativePaise) {
    }

    public record DailyTotals(long pfPaise, long interestPaise, long penaltyPaise, long revenuePaise) {
    }

    public record Daily(List<DayRow> days, DailyTotals totals) {
    }

    // ---- pre-closure week -----------------------------------------------------------------

    public record PreDay(String date, long dueCount, long dueAmountPaise, long preclosedCount, long preclosedPaise,
                         long pendingCount, long pendingPaise, long receivedCount, long receivedPaise,
                         Double collectionPct) {
    }

    public record PreclosureWeek(String anchor, List<PreDay> rows, PreDay total) {
    }

    // ---- AUM ------------------------------------------------------------------------------

    public record AumRow(String bucket, long cases, long principalPaise, long owedPaise, Double portfolioPct) {
    }

    public record AumChips(Double currentPct, Double d1to60Pct, Double d61to90Pct, Double d90PlusPct,
                           long currentCount, long d1to60Count, long d61to90Count, long d90PlusCount) {
    }

    public record Aum(String asOf, List<AumRow> rows, AumRow total, AumChips chips) {
    }

    // ---- collection analysis --------------------------------------------------------------

    public record AnalysisRow(String key, String label, long loans, long principalPaise, long repayablePaise,
                              long collectedCount, long collectedPaise, Long averagePrincipalPaise,
                              Double collectedPct) {
    }

    public record CollectionAnalysis(String date, String groupBy, List<AnalysisRow> rows) {
    }

    // ---- team -----------------------------------------------------------------------------

    public record RoleCount(String role, long count) {
    }

    public record HeadScore(long staffId, String name, String role, Double score) {
    }

    public record Team(long total, long active, long inactive, long invited, List<RoleCount> byRole,
                       List<HeadScore> heads) {
    }

    // ---- calendar -------------------------------------------------------------------------

    public record CalendarDay(String date, long count, Long amountPaise) {
    }

    public record Calendar(String month, String mode, List<CalendarDay> days, long daysWithData, long totalCount,
                           Long totalAmountPaise) {
    }

    // ---- geo / companies ------------------------------------------------------------------

    public record GeoRow(String key, String label, String state, long cases, long principalPaise, long closedCount,
                         long dueCount, Double closeRate, long prevPeriodCases) {
    }

    public record Geo(List<GeoRow> states, List<GeoRow> pincodes) {
    }

    public record CompanyUsers(String company, long users, Double sharePct, Long averageSalaryPaise,
                               String employmentType) {
    }

    public record CompanyPerformance(String company, long disbursed, long disbursedPaise, long repayablePaise,
                                     long collectedPaise, Double collectionPct) {
    }

    public record Companies(long totalCompanies, long totalUsers, List<CompanyUsers> users,
                            List<CompanyPerformance> performance) {
    }

    // ---- collection allocation ------------------------------------------------------------

    public record AllocRow(Long staffId, String name, long assigned, long closed, long freshCount, long freshPaise,
                           long reloanCount, long reloanPaise, long totalCount, long totalPaise) {
    }

    public record AllocCards(long assigned, long closed, long collectedCount, long collectedPaise) {
    }

    public record AllocCardsWithPrevious(long assigned, long closed, long collectedCount, long collectedPaise,
                                         AllocCards previous) {
    }

    public record Allocation(AllocCardsWithPrevious cards, List<AllocRow> rows, AllocRow total) {
    }

    // ---- leaderboard ----------------------------------------------------------------------

    public record LeaderRow(long staffId, String name, int rank, Double score, Integer stars, long volume,
                            boolean self, List<Factor> factors) {
    }

    public record Leaderboard(String board, int members, List<LeaderRow> rows) {
    }

    // ---- role view ------------------------------------------------------------------------

    public record RoleCard(String key, String label, Long count, Long amountPaise, Long previousCount,
                           Long previousAmountPaise, Metric metric, String tone) {
    }

    public record Chart(String id, String title, String kind, List<String> keys, Map<String, String> labels,
                        List<Map<String, Object>> points) {
    }

    public record Ptp(long totalAfterAssignmentPaise, long sameDayPaise, String latestPaymentOn) {
    }

    public record RoleView(String view, List<RoleCard> cards, List<Chart> charts, List<Factor> factors, Double score,
                           Ptp ptp, List<RateRow> table) {
    }

    // ---- records (drill-down) -------------------------------------------------------------

    public record RecordRow(Long applicationId, Long loanId, Long customerId, String customerName,
                            String mobileLast4, String status, String segment, Long amountPaise, Long owedPaise,
                            String disbursedOn, String dueDate, String closedOn, String assigneeName, String state) {
    }

    public record Records(List<RecordRow> rows, long total, long freshCount, long reloanCount, Long sumPaise) {
    }

    // ---- targets --------------------------------------------------------------------------

    public record TargetRow(String month, Long disbursalTargetPaise, int collectionTargetBp) {
    }

    public record TargetUpdate(Long disbursalTargetPaise, int collectionTargetBp) {
    }
}
