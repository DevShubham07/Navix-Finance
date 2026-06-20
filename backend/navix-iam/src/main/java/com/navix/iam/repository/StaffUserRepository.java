package com.navix.iam.repository;

import com.navix.iam.domain.StaffRole;
import com.navix.iam.entity.StaffUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link StaffUser}.
 * TODO: add query methods as access-control needs emerge.
 */
public interface StaffUserRepository extends JpaRepository<StaffUser, Long> {

    Optional<StaffUser> findByEmail(String email);

    List<StaffUser> findByRole(StaffRole role);
}
