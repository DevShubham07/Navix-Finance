package com.navix.loan.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.BureauPhone;
import com.navix.loan.repository.BureauPhoneRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Keeps {@code bureau_phone} (V80) in step with an application's BUREAU raw response. Runs in its own
 * transaction and swallows every failure: indexing is a derived convenience and must never break a pull.
 * Logs never carry numbers.
 */
@Slf4j
@Service
public class BureauPhoneIndexer {

    private final BureauPhoneRepository phones;
    private final LoanApplicationRepository applications;
    private final CustomerProfileRepository profiles;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate tx;

    public BureauPhoneIndexer(BureauPhoneRepository phones, LoanApplicationRepository applications,
                              CustomerProfileRepository profiles, ObjectMapper objectMapper,
                              PlatformTransactionManager txManager) {
        this.phones = phones;
        this.applications = applications;
        this.profiles = profiles;
        this.objectMapper = objectMapper;
        this.tx = new TransactionTemplate(txManager);
        this.tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Replaces the application's rows from {@code row} (a BUREAU verification). Inside a live
     * transaction the work runs after it commits (a rolled-back pull leaves no rows) and 0 is
     * returned; otherwise it runs now and returns the rows stored. Never throws.
     */
    public int index(Long applicationId, ApplicationVerification row, Instant pulledAt) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    run(applicationId, row, pulledAt);
                }
            });
            return 0;
        }
        return run(applicationId, row, pulledAt);
    }

    private int run(Long applicationId, ApplicationVerification row, Instant pulledAt) {
        try {
            Integer n = tx.execute(s -> replace(applicationId, row, pulledAt));
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            log.warn("bureau phone index failed application={} error={}", applicationId, e.getClass().getSimpleName());
            return 0;
        }
    }

    private int replace(Long applicationId, ApplicationVerification row, Instant pulledAt) {
        Long customerId = applications.findById(applicationId).map(a -> a.getCustomerId()).orElse(null);
        if (customerId == null || row == null || row.getRawResponse() == null) {
            return 0;
        }
        JsonNode raw;
        JsonNode derived;
        try {
            raw = objectMapper.readTree(row.getRawResponse());
            derived = row.getDerived() == null ? null : objectMapper.readTree(row.getDerived());
        } catch (Exception e) {
            throw new IllegalStateException("unparseable bureau json");
        }
        Set<String> registered = new HashSet<>(profiles.findMobilesForCustomer(customerId));
        boolean mismatch = derived != null && !derived.path("identityMismatch").isMissingNode()
                && !derived.path("identityMismatch").isNull();
        phones.deleteByApplicationId(applicationId);
        List<BureauPhone> rows = BureauMobiles.extract(raw, registered).stream()
                .filter(p -> "MOBILE".equals(p.kind()))
                // Request echoes are our input, not bureau data; so is our own registered number.
                .filter(p -> !p.registered() && !"Our request".equals(p.context())
                        && !"Application".equals(p.context()))
                .map(p -> {
                    BureauPhone b = new BureauPhone();
                    b.setCustomerId(customerId);
                    b.setApplicationId(applicationId);
                    b.setMobile(p.normalized());
                    b.setSource(p.source());
                    b.setReportedDate(p.reportedDate());
                    b.setIdentityMismatch(mismatch);
                    b.setPulledAt(pulledAt);
                    return b;
                }).toList();
        phones.saveAll(rows);
        return rows.size();
    }
}
