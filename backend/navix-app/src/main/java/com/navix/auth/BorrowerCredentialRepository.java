package com.navix.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link BorrowerCredential} (id = the mobile-derived customer id). */
@Repository
public interface BorrowerCredentialRepository extends JpaRepository<BorrowerCredential, Long> {
}
