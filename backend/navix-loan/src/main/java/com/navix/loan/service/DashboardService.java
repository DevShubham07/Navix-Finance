package com.navix.loan.service;

import com.navix.loan.domain.PaymentStatus;
import com.navix.loan.dto.DashboardDtos.TrendPoint;
import com.navix.loan.dto.DashboardDtos.TrendResponse;
import com.navix.loan.entity.ApplicationEvent;
import com.navix.loan.entity.Loan;
import com.navix.loan.entity.Payment;
import com.navix.loan.repository.ApplicationEventRepository;
import com.navix.loan.repository.LoanRepository;
import com.navix.loan.repository.PaymentRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates day-by-day activity for the staff dashboard trend charts from existing timestamps:
 * applications (from the {@code CREATE} audit event), disbursals (loan {@code disbursedOn}) and
 * verified repayments (payment {@code paidOn}). No new storage — derived on read.
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final ApplicationEventRepository eventRepository;
    private final LoanRepository loanRepository;
    private final PaymentRepository paymentRepository;

    @Transactional(readOnly = true)
    public TrendResponse trends(int days) {
        int window = Math.max(1, Math.min(days, 180));
        LocalDate today = LocalDate.now(IST);
        LocalDate start = today.minusDays(window - 1L);

        Map<LocalDate, long[]> byDay = new HashMap<>(); // [applications, disbursed, repaid]
        for (int i = 0; i < window; i++) {
            byDay.put(start.plusDays(i), new long[3]);
        }

        for (ApplicationEvent e : eventRepository.findByAction("CREATE")) {
            if (e.getAt() == null) continue;
            LocalDate d = e.getAt().atZone(IST).toLocalDate();
            long[] slot = byDay.get(d);
            if (slot != null) slot[0]++;
        }
        for (Loan l : loanRepository.findAll()) {
            LocalDate d = l.getDisbursedOn();
            if (d == null) continue;
            long[] slot = byDay.get(d);
            if (slot != null) slot[1]++;
        }
        for (Payment p : paymentRepository.findAll()) {
            if (p.getStatus() != PaymentStatus.VERIFIED || p.getPaidOn() == null) continue;
            long[] slot = byDay.get(p.getPaidOn());
            if (slot != null) slot[2]++;
        }

        List<TrendPoint> points = new ArrayList<>(window);
        for (int i = 0; i < window; i++) {
            LocalDate d = start.plusDays(i);
            long[] slot = byDay.get(d);
            points.add(new TrendPoint(d.toString(), slot[0], slot[1], slot[2]));
        }

        // This-week vs last-week deltas (trailing 7 days vs the 7 before).
        long[] thisWeek = new long[3];
        long[] lastWeek = new long[3];
        for (int i = 0; i < window; i++) {
            long[] slot = byDay.get(start.plusDays(i));
            int fromEnd = window - 1 - i; // 0 = today
            if (fromEnd < 7) {
                add(thisWeek, slot);
            } else if (fromEnd < 14) {
                add(lastWeek, slot);
            }
        }

        return new TrendResponse(points,
                thisWeek[0], lastWeek[0],
                thisWeek[1], lastWeek[1],
                thisWeek[2], lastWeek[2]);
    }

    private static void add(long[] acc, long[] slot) {
        acc[0] += slot[0];
        acc[1] += slot[1];
        acc[2] += slot[2];
    }
}
