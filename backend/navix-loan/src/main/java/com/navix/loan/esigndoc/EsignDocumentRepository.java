package com.navix.loan.esigndoc;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EsignDocumentRepository extends JpaRepository<EsignDocument, Long> {

    List<EsignDocument> findAllByOrderByCreatedAtDescIdDesc();

    Optional<EsignDocument> findFirstByContractId(String contractId);
}
