package com.navix.income.domain;

/**
 * Risk category buckets for a borrower.
 *
 * <p>Categories affect the granted limit and the depth of checks performed,
 * but they do NOT affect pricing (fees/interest are flat across categories).
 *
 * <ul>
 *   <li>{@link #A} - lowest risk, strongest profile.</li>
 *   <li>{@link #B} - low/medium risk.</li>
 *   <li>{@link #C} - elevated risk, additional checks.</li>
 *   <li>{@link #D} - highest risk (may be declined or heavily limited).</li>
 * </ul>
 *
 * TODO: scoring bands that map a numeric score -> category are TBD
 * (see {@code RiskScoringService}).
 */
public enum RiskCategory {
    A,
    B,
    C,
    D
}
