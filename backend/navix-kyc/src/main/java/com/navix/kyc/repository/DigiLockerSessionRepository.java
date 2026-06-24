package com.navix.kyc.repository;

import com.navix.kyc.entity.DigiLockerSession;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Persistence for {@link DigiLockerSession}.
 */
@Repository
public interface DigiLockerSessionRepository extends JpaRepository<DigiLockerSession, Long> {

    List<DigiLockerSession> findByBorrowerId(Long borrowerId);
}
