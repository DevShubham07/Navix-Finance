package com.navix.loan.service;

import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.entity.ApplicationDocument;
import com.navix.loan.repository.ApplicationDocumentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The S3 + {@code application_document} half of credit-brief generation, deliberately split out of
 * {@link CreditBriefService} so it can run in its <b>own</b> transaction.
 *
 * <p>{@link CreditBriefService#generate} advertises that a PDF/S3 failure "leaves the rating intact",
 * and it catches everything to make good on that. Catching is not enough: once a statement fails
 * inside the caller's transaction Postgres marks the whole transaction aborted (SQLState {@code 25P02})
 * and every later statement — including the caller's own reads — fails too. That is exactly how a
 * read of {@code GET /api/customers/{id}} turned into a 500: the lazy brief regeneration attempted an
 * INSERT inside a read-only transaction, was swallowed, and poisoned the read that followed.
 *
 * <p>{@link Propagation#REQUIRES_NEW} suspends the caller's transaction and runs this work on a fresh
 * one, so a failure here can only roll back <em>this</em> unit and the swallow upstream is honest.
 * It must live on a separate bean: a self-invocation would bypass the proxy and change nothing.
 */
@Service
@RequiredArgsConstructor
public class CreditBriefPdfWriter {

    private final DocumentStoragePort storage;
    private final ApplicationDocumentRepository documentRepo;

    /**
     * Store the rendered brief at {@code key} and upsert the application's {@code CREDIT_BRIEF}
     * document row to point at it. Throws on failure — the caller decides whether that is fatal.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void storeAndRegister(Long appId, String key, byte[] pdf) {
        storage.store(key, pdf, CreditBriefService.CONTENT_TYPE);
        ApplicationDocument doc = documentRepo
                .findFirstByApplicationIdAndDocTypeOrderByIdDesc(appId, CreditBriefService.DOC_TYPE)
                .orElseGet(ApplicationDocument::new);
        doc.setApplicationId(appId);
        doc.setDocType(CreditBriefService.DOC_TYPE);
        doc.setFileName(CreditBriefService.FILE_NAME);
        doc.setContentType(CreditBriefService.CONTENT_TYPE);
        doc.setSizeBytes((long) pdf.length);
        doc.setS3ObjectKey(key);
        doc.setData(null); // S3-backed: keep the "exactly one of data / s3ObjectKey" invariant
        documentRepo.save(doc);
    }
}
