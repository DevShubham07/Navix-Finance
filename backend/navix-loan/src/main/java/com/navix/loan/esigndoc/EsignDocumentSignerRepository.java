package com.navix.loan.esigndoc;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EsignDocumentSignerRepository extends JpaRepository<EsignDocumentSigner, Long> {

    List<EsignDocumentSigner> findByDocumentIdOrderBySignerOrder(Long documentId);

    List<EsignDocumentSigner> findByDocumentIdIn(Collection<Long> documentIds);
}
