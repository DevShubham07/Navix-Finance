package com.navix.loan.repository;

import com.navix.loan.entity.CustomerCallLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for staff call logs on a customer. */
@Repository
public interface CustomerCallLogRepository extends JpaRepository<CustomerCallLog, Long> {

    /** One customer's call logs, newest first. */
    List<CustomerCallLog> findByCustomerIdOrderByIdDesc(Long customerId);

    /** Only the calls tagged to one loan (newest first) — backs the {@code ?loanId=} filter. */
    List<CustomerCallLog> findByCustomerIdAndLoanIdOrderByIdDesc(Long customerId, Long loanId);
}
