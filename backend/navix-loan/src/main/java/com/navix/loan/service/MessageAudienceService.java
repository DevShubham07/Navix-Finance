package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.featureflag.FeatureFlagService;
import com.navix.common.security.ActorContext;
import com.navix.loan.domain.LoanStatus;
import com.navix.loan.dto.MessagingDtos.AudienceView;
import com.navix.loan.entity.Loan;
import com.navix.loan.repository.LoanRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Who a "Send to" group on the Templates page means: DPD ranges by loan, the rest by customer segment. */
@Service
@RequiredArgsConstructor
public class MessageAudienceService {

    /** ponytail: one browser loops the sends, so a very large group goes out in pieces. */
    static final int CAP = 2000;
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final Set<LoanStatus> LIVE = Set.of(LoanStatus.ACTIVE, LoanStatus.OVERDUE, LoanStatus.IN_COLLECTIONS);
    private static final Map<String, int[]> OVERDUE = Map.of(
            "OVERDUE_1_7", new int[] {1, 7},
            "OVERDUE_8_30", new int[] {8, 30},
            "OVERDUE_31_60", new int[] {31, 60},
            "OVERDUE_61_90", new int[] {61, 90},
            "OVERDUE_90_PLUS", new int[] {91, Integer.MAX_VALUE});
    /** Blocked and Unallocated are left out on purpose: one was barred, the other is a staff work view. */
    private static final Set<String> SEGMENTS = Set.of("active", "closed", "reloan", "approved",
            "disbursementPending", "incomplete", "pending", "review", "hold", "rejected");

    private final FeatureFlagService featureFlags;
    private final LoanRepository loans;
    private final CustomerBookQuery book;
    private final LoanMath loanMath;

    private Clock clock = Clock.system(IST);

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public AudienceView audience(String group) {
        if (!"ADMIN".equals(ActorContext.get().role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "Only an admin can send customer messages");
        }
        if (!featureFlags.isEnabled(CustomerMessageService.FLAG, false)) {
            throw new BusinessException("FEATURE_DISABLED", "Customer messaging is switched off");
        }
        LocalDate today = LocalDate.now(clock);
        if (SEGMENTS.contains(group)) {
            var filter = new CustomerBookQuery.BookFilter("", null, null, group, null, false, null, today);
            long count = book.count(filter);
            return new AudienceView(group, count, book.pageIds(filter, 0, CAP), count > CAP);
        }
        int[] range = OVERDUE.get(group);
        Predicate<Loan> in;
        if (range != null) {
            in = l -> {
                int days = loanMath.daysPastDue(l.getDueDate(), today);
                return days >= range[0] && days <= range[1];
            };
        } else if ("DUE_SOON".equals(group)) {
            in = l -> l.getStatus() == LoanStatus.ACTIVE && !l.getDueDate().isBefore(today)
                    && !l.getDueDate().isAfter(today.plusDays(3));
        } else {
            throw new BusinessException("INVALID_GROUP", "Unknown customer group");
        }
        List<Long> ids = loans.findByStatusIn(LIVE).stream()
                .filter(l -> l.getDueDate() != null).filter(in).map(Loan::getCustomerId).distinct().toList();
        return new AudienceView(group, ids.size(), ids.stream().limit(CAP).toList(), ids.size() > CAP);
    }
}
