package com.navix.onboarding.repository;

import com.navix.onboarding.entity.CoApplicant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link CoApplicant}.
 */
public interface CoApplicantRepository extends JpaRepository<CoApplicant, Long> {

    List<CoApplicant> findByBorrowerId(Long borrowerId);
}
