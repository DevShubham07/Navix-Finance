package com.navix.notification.repository;

import com.navix.notification.entity.EmailSuppression;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/** Persistence for the {@link EmailSuppression} list (bounced / complained email addresses). */
public interface EmailSuppressionRepository extends JpaRepository<EmailSuppression, Long> {

    boolean existsByEmailIgnoreCase(String email);

    Optional<EmailSuppression> findByEmailIgnoreCase(String email);
}
