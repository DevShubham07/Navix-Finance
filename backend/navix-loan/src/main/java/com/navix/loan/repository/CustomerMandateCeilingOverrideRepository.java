package com.navix.loan.repository;

import com.navix.loan.entity.CustomerMandateCeilingOverride;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerMandateCeilingOverrideRepository
        extends JpaRepository<CustomerMandateCeilingOverride, Long> {
}
