package com.navix.loan.repository;

import com.navix.loan.entity.ReferralCode;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Spring Data JPA repository for {@link ReferralCode} (one shareable code per customer). */
public interface ReferralCodeRepository extends JpaRepository<ReferralCode, Long> {

    Optional<ReferralCode> findByCustomerId(Long customerId);

    Optional<ReferralCode> findByCode(String code);

    boolean existsByCode(String code);
}
