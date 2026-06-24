package com.navix.collections.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.collections.domain.DpdBucket;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic tests for {@link DpdCalculator}: days-past-due arithmetic and bucket
 * banding across every band boundary. No mocks — constructed directly.
 */
class DpdCalculatorTest {

    private final DpdCalculator calc = new DpdCalculator();

    private static final LocalDate DUE = LocalDate.of(2026, 6, 1);

    @Test
    void daysPastDueIsZeroBeforeAndOnDueDate() {
        assertThat(calc.daysPastDue(DUE, DUE.minusDays(5))).isZero();
        assertThat(calc.daysPastDue(DUE, DUE)).isZero();
    }

    @Test
    void daysPastDueCountsDaysAfterDueDate() {
        assertThat(calc.daysPastDue(DUE, DUE.plusDays(1))).isEqualTo(1);
        assertThat(calc.daysPastDue(DUE, DUE.plusDays(45))).isEqualTo(45);
    }

    @Test
    void daysPastDueNullSafe() {
        assertThat(calc.daysPastDue(null, DUE)).isZero();
        assertThat(calc.daysPastDue(DUE, null)).isZero();
    }

    @Test
    void bucketMapsUpcomingForZeroOrNegative() {
        assertThat(calc.bucket(-10)).isEqualTo(DpdBucket.UPCOMING);
        assertThat(calc.bucket(0)).isEqualTo(DpdBucket.UPCOMING);
    }

    @Test
    void bucketMapsT0T7BandInclusive() {
        assertThat(calc.bucket(1)).isEqualTo(DpdBucket.T0_T7);
        assertThat(calc.bucket(7)).isEqualTo(DpdBucket.T0_T7);
    }

    @Test
    void bucketMapsT8T30BandInclusive() {
        assertThat(calc.bucket(8)).isEqualTo(DpdBucket.T8_T30);
        assertThat(calc.bucket(30)).isEqualTo(DpdBucket.T8_T30);
    }

    @Test
    void bucketMapsT30T60BandInclusive() {
        assertThat(calc.bucket(31)).isEqualTo(DpdBucket.T30_T60);
        assertThat(calc.bucket(60)).isEqualTo(DpdBucket.T30_T60);
    }

    @Test
    void bucketMapsT60T90BandInclusive() {
        assertThat(calc.bucket(61)).isEqualTo(DpdBucket.T60_T90);
        assertThat(calc.bucket(90)).isEqualTo(DpdBucket.T60_T90);
    }

    @Test
    void bucketMapsT90PlusAbove90() {
        assertThat(calc.bucket(91)).isEqualTo(DpdBucket.T90_PLUS);
        assertThat(calc.bucket(365)).isEqualTo(DpdBucket.T90_PLUS);
    }

    @Test
    void bucketForComposesDaysAndBanding() {
        assertThat(calc.bucketFor(DUE, DUE.minusDays(1))).isEqualTo(DpdBucket.UPCOMING);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(5))).isEqualTo(DpdBucket.T0_T7);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(20))).isEqualTo(DpdBucket.T8_T30);
        assertThat(calc.bucketFor(DUE, DUE.plusDays(100))).isEqualTo(DpdBucket.T90_PLUS);
    }
}
