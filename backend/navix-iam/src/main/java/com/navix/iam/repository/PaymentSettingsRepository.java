package com.navix.iam.repository;

import com.navix.iam.entity.PaymentSettings;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for the singleton {@link PaymentSettings} row.
 */
public interface PaymentSettingsRepository extends JpaRepository<PaymentSettings, Long> {

    /** The (single) settings row, lowest id first. */
    Optional<PaymentSettings> findFirstByOrderByIdAsc();
}
