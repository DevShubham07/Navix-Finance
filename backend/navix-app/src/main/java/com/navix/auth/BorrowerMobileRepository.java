package com.navix.auth;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/** Persistence for {@link BorrowerMobile} (id = the mobile-derived customer id). */
@Repository
public interface BorrowerMobileRepository extends JpaRepository<BorrowerMobile, Long> {
}
