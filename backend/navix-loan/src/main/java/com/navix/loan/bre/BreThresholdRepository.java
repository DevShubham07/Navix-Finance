package com.navix.loan.bre;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BreThresholdRepository extends JpaRepository<BreThreshold, Long> {

    Optional<BreThreshold> findByThresholdKey(String thresholdKey);
}
