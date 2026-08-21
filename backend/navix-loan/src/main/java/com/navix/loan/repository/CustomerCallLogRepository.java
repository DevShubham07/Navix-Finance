package com.navix.loan.repository;

import com.navix.loan.entity.CustomerCallLog;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/** Persistence for staff call logs on a customer. */
@Repository
public interface CustomerCallLogRepository extends JpaRepository<CustomerCallLog, Long> {

    /** One customer's call logs, newest first. */
    List<CustomerCallLog> findByCustomerIdOrderByIdDesc(Long customerId);

    /** Only the calls tagged to one loan (newest first) — backs the {@code ?loanId=} filter. */
    List<CustomerCallLog> findByCustomerIdAndLoanIdOrderByIdDesc(Long customerId, Long loanId);

    /**
     * Calls logged per staff member in the half-open window {@code [from, to)} — the telecaller half
     * of the staff-performance "calls" metric. Rides {@code idx_customer_call_log_staff_at} (V60).
     *
     * <p>Keyed off {@code createdByStaffId}, never the inherited {@code createdBy}: that holds a
     * display name a staffer can change on themselves, so it cannot identify anyone reliably. Rows
     * from before V59 carry no staff id and are excluded by the {@code in} clause.
     */
    @Query("""
            select c.createdByStaffId as staffId, count(c) as count from CustomerCallLog c
            where c.createdByStaffId in :staffIds and c.createdAt >= :from and c.createdAt < :to
            group by c.createdByStaffId
            """)
    List<StaffCallCount> countByStaffInWindow(@Param("staffIds") Collection<Long> staffIds,
                                              @Param("from") Instant from,
                                              @Param("to") Instant to);

    /** Projection for {@link #countByStaffInWindow(Collection, Instant, Instant)}. */
    interface StaffCallCount {
        Long getStaffId();

        Long getCount();
    }
}
