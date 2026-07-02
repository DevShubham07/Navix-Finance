package com.navix.loan.dto;

import java.util.List;

/** DTOs for the staff dashboard trend charts. */
public final class DashboardDtos {

    private DashboardDtos() {
    }

    /** One day's counts (ISO date). */
    public record TrendPoint(String date, long applications, long disbursed, long repaid) {
    }

    /**
     * A window of daily trend points (oldest→newest) plus this-week-vs-last-week deltas for the
     * dashboard headline tiles.
     */
    public record TrendResponse(
            List<TrendPoint> points,
            long applicationsThisWeek,
            long applicationsLastWeek,
            long disbursedThisWeek,
            long disbursedLastWeek,
            long repaidThisWeek,
            long repaidLastWeek) {
    }
}
