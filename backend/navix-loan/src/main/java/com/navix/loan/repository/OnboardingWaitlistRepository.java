package com.navix.loan.repository;

import com.navix.loan.entity.OnboardingWaitlist;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingWaitlistRepository extends JpaRepository<OnboardingWaitlist, Long> {

    Optional<OnboardingWaitlist> findByCustomerId(Long customerId);

    boolean existsByPanAndCustomerIdNot(String pan, Long customerId);

    boolean existsByAadhaarAndCustomerIdNot(String aadhaar, Long customerId);

    List<OnboardingWaitlist> findAllByOrderByCreatedAtDesc();
}
