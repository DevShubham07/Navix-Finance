package com.navix.iam.repository;

import com.navix.iam.entity.StaffInvite;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link StaffInvite}.
 * TODO: add query for pending (unaccepted, unexpired) invites.
 */
public interface StaffInviteRepository extends JpaRepository<StaffInvite, Long> {

    Optional<StaffInvite> findByToken(String token);

    Optional<StaffInvite> findByEmail(String email);
}
