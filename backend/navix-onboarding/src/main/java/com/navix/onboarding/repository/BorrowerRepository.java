package com.navix.onboarding.repository;

import com.navix.onboarding.entity.Borrower;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Persistence for {@link Borrower}.
 * TODO: add finders by mobile / pan / officialEmail as the flow needs them.
 */
@Repository
public interface BorrowerRepository extends JpaRepository<Borrower, Long> {

    Optional<Borrower> findByMobile(String mobile);

    Optional<Borrower> findByPan(String pan);
}
