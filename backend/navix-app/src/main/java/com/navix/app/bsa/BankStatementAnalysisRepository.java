package com.navix.app.bsa;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BankStatementAnalysisRepository extends JpaRepository<BankStatementAnalysis, Long> {

    List<BankStatementAnalysis> findByCustomerIdOrderByCreatedAtDesc(Long customerId);

    Optional<BankStatementAnalysis> findFirstByCustomerIdOrderByCreatedAtDesc(Long customerId);
}
