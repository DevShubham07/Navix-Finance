package com.navix.onboarding.repository;

import com.navix.onboarding.entity.SignupApplication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Persistence for {@link SignupApplication}.
 * TODO: add finder by borrowerId for the current in-progress application.
 */
@Repository
public interface SignupApplicationRepository extends JpaRepository<SignupApplication, Long> {

    Optional<SignupApplication> findByBorrowerId(Long borrowerId);
}
