package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LoanMath} — the integer-paise money engine.
 * All amounts are in paise (₹1 = 100 paise). ₹10,000 = 1_000_000 paise.
 */
class LoanMathTest {

    private final LoanMath math = new LoanMath();

    private static final long RS_10_000 = 1_000_000L;

    @Nested
    @DisplayName("Fee, GST and net disbursed")
    class FeeGstNet {

        @Test
        @DisplayName("₹10,000 → fee ₹1,000, GST ₹180, net ₹8,820")
        void workedExample() {
            assertThat(math.processingFeePaise(RS_10_000)).isEqualTo(100_000L);
            assertThat(math.gstPaise(RS_10_000)).isEqualTo(18_000L);
            assertThat(math.netDisbursedPaise(RS_10_000)).isEqualTo(882_000L);
        }

        @Test
        @DisplayName("processing fee rounds HALF_UP at the half-paisa")
        void halfUpRounding() {
            // 1_000_005 × 0.10 = 100_000.5 → 100_001 (round up)
            assertThat(math.processingFeePaise(1_000_005L)).isEqualTo(100_001L);
            // 1_000_001 × 0.10 = 100_000.1 → 100_000 (round down)
            assertThat(math.processingFeePaise(1_000_001L)).isEqualTo(100_000L);
        }
    }

    @Nested
    @DisplayName("Interest and total repayable")
    class InterestRepayable {

        @Test
        @DisplayName("interest = 1%/day on principal")
        void interest() {
            assertThat(math.interestPaise(RS_10_000, 30)).isEqualTo(300_000L);
            assertThat(math.interestPaise(RS_10_000, 27)).isEqualTo(270_000L);
            assertThat(math.interestPaise(RS_10_000, 10)).isEqualTo(100_000L);
        }

        @Test
        @DisplayName("negative days accrue no interest")
        void negativeDays() {
            assertThat(math.interestPaise(RS_10_000, -5)).isZero();
        }

        @Test
        @DisplayName("total repayable = principal + interest (fee/GST excluded)")
        void totalRepayable() {
            assertThat(math.totalRepayablePaise(RS_10_000, 30)).isEqualTo(1_300_000L);
            assertThat(math.totalRepayablePaise(RS_10_000, 27)).isEqualTo(1_270_000L);
            // Prepay on day 10 → repay ₹11,000
            assertThat(math.totalRepayablePaise(RS_10_000, 10)).isEqualTo(1_100_000L);
        }
    }

    @Nested
    @DisplayName("Late penalty (2%/day, capped at 30 days)")
    class Penalty {

        @Test
        void accruesPerDay() {
            assertThat(math.latePenaltyPaise(RS_10_000, 5)).isEqualTo(100_000L);
        }

        @Test
        void cappedAtThirtyDays() {
            assertThat(math.latePenaltyPaise(RS_10_000, 40)).isEqualTo(600_000L);
            assertThat(math.latePenaltyPaise(RS_10_000, 30)).isEqualTo(600_000L);
        }

        @Test
        void noPenaltyBeforeDue() {
            assertThat(math.latePenaltyPaise(RS_10_000, 0)).isZero();
            assertThat(math.latePenaltyPaise(RS_10_000, -3)).isZero();
        }
    }

    @Nested
    @DisplayName("Eligible limit (25% of salary, floored to ₹100)")
    class EligibleLimit {

        @Test
        void quarterOfSalary() {
            // ₹40,000 → ₹10,000
            assertThat(math.eligibleLimitPaise(4_000_000L)).isEqualTo(1_000_000L);
            // ₹50,000 → ₹12,500
            assertThat(math.eligibleLimitPaise(5_000_000L)).isEqualTo(1_250_000L);
        }

        @Test
        void flooredToNearestHundredRupees() {
            // ₹40,123 → 25% = ₹10,030.75 → floor to ₹10,000
            assertThat(math.eligibleLimitPaise(4_012_300L)).isEqualTo(1_000_000L);
        }
    }

    @Nested
    @DisplayName("Outstanding (compute-on-read)")
    class Outstanding {

        @Test
        @DisplayName("before due: principal + interest to date, less payments")
        void beforeDue() {
            assertThat(math.outstandingPaise(RS_10_000, 27, 0, 0L)).isEqualTo(1_270_000L);
            assertThat(math.outstandingPaise(RS_10_000, 27, 0, 500_000L)).isEqualTo(770_000L);
        }

        @Test
        @DisplayName("overdue: adds capped penalty for days past grace")
        void overdue() {
            // 35 days held + 5 days late → 1_350_000 + 100_000
            assertThat(math.outstandingPaise(RS_10_000, 35, 5, 0L)).isEqualTo(1_450_000L);
        }

        @Test
        @DisplayName("never negative once fully paid")
        void fullyPaid() {
            assertThat(math.outstandingPaise(RS_10_000, 30, 0, 2_000_000L)).isZero();
        }
    }

    @Nested
    @DisplayName("Days past due")
    class DaysPastDue {

        @Test
        void afterDueDate() {
            assertThat(math.daysPastDue(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 7, 5)))
                    .isEqualTo(5);
        }

        @Test
        void onOrBeforeDueDateIsZero() {
            assertThat(math.daysPastDue(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 30)))
                    .isZero();
            assertThat(math.daysPastDue(LocalDate.of(2026, 6, 30), LocalDate.of(2026, 6, 20)))
                    .isZero();
        }
    }

    @Nested
    @DisplayName("Due date = latest salary credit ≤ 40 days after disbursement")
    class DueDate {

        @Test
        @DisplayName("only one salary in window → that salary day")
        void singleSalaryInWindow() {
            // Salary on 30th, disbursed 3 Jun → due 30 Jun (~27 days; 30 Jul is > 40 days out)
            assertThat(math.dueDateFromSalary(LocalDate.of(2026, 6, 3), 30))
                    .isEqualTo(LocalDate.of(2026, 6, 30));
        }

        @Test
        @DisplayName("two salaries in window → the later one")
        void picksLatestWithinWindow() {
            // Salary on 30th, disbursed 25 Jun → 30 Jun (~5d) and 30 Jul (~35d) both ≤ 40d → 30 Jul
            assertThat(math.dueDateFromSalary(LocalDate.of(2026, 6, 25), 30))
                    .isEqualTo(LocalDate.of(2026, 7, 30));
        }

        @Test
        @DisplayName("salary day 31 clamps to month length (non-leap Feb)")
        void clampsShortMonth() {
            // Disbursed 25 Jan 2026, salary day 31 → 31 Jan (6d) and 28 Feb (34d) ≤ 40d → 28 Feb
            assertThat(math.dueDateFromSalary(LocalDate.of(2026, 1, 25), 31))
                    .isEqualTo(LocalDate.of(2026, 2, 28));
        }

        @Test
        @DisplayName("salary day 31 clamps to 29 Feb in a leap year")
        void clampsLeapFeb() {
            // 2028 is a leap year. Disbursed 25 Jan → 31 Jan and 29 Feb (35d) → 29 Feb
            assertThat(math.dueDateFromSalary(LocalDate.of(2028, 1, 25), 31))
                    .isEqualTo(LocalDate.of(2028, 2, 29));
        }
    }
}
