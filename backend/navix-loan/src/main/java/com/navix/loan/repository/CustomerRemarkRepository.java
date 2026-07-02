package com.navix.loan.repository;

import com.navix.loan.entity.CustomerRemark;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for staff remarks on a customer. */
@Repository
public interface CustomerRemarkRepository extends JpaRepository<CustomerRemark, Long> {

    /** One customer's remarks, newest first. */
    List<CustomerRemark> findByCustomerIdOrderByIdDesc(Long customerId);
}
