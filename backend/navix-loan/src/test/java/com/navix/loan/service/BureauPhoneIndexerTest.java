package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.entity.BureauPhone;
import com.navix.loan.entity.LoanApplication;
import com.navix.loan.repository.ApplicationVerificationRepository;
import com.navix.loan.repository.BureauPhoneRepository;
import com.navix.loan.repository.CustomerProfileRepository;
import com.navix.loan.repository.LoanApplicationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.SliceImpl;
import org.springframework.transaction.PlatformTransactionManager;

class BureauPhoneIndexerTest {

    private final BureauPhoneRepository phones = mock(BureauPhoneRepository.class);
    private final LoanApplicationRepository applications = mock(LoanApplicationRepository.class);
    private final CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-10T00:00:00Z");
    private BureauPhoneIndexer indexer;

    @BeforeEach
    void setUp() {
        indexer = new BureauPhoneIndexer(phones, applications, profiles, new ObjectMapper(),
                mock(PlatformTransactionManager.class));
        LoanApplication a = new LoanApplication();
        a.setId(7L);
        a.setCustomerId(3L);
        when(applications.findById(7L)).thenReturn(Optional.of(a));
        when(profiles.findMobilesForCustomer(3L)).thenReturn(List.of("9999999999"));
    }

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    private static ApplicationVerification row(String derived) {
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(7L);
        v.setDerived(derived);
        v.setRawResponse("{\"data\":{\"PERSONAL-INFO-VARIATION\":{\"PHONE-NUMBER-VARIATIONS\":{\"VARIATION\":["
                + "{\"VALUE\":\"9000000001\",\"REPORTED-DATE\":\"31-07-2026\"},"
                + "{\"VALUE\":\"0221234567\",\"REPORTED-DATE\":\"31-07-2026\"}]}}}}");
        return v;
    }

    @Test
    @SuppressWarnings("unchecked")
    void replacesRowsStoringOnlyMobiles() {
        assertThat(indexer.index(7L, row("{\"identityMismatch\":\"PAN differs\"}"), NOW)).isEqualTo(1);
        verify(phones).deleteByApplicationId(7L);
        ArgumentCaptor<List<BureauPhone>> cap = ArgumentCaptor.forClass(List.class);
        verify(phones).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        BureauPhone b = cap.getValue().get(0);
        assertThat(b.getMobile()).isEqualTo("9000000001");
        assertThat(b.getCustomerId()).isEqualTo(3L);
        assertThat(b.getSource()).isEqualTo("CRIF");
        assertThat(b.isIdentityMismatch()).isTrue();
    }

    @Test
    void cleanDerivedIsNotFlagged() {
        indexer.index(7L, row("{\"source\":\"x\"}"), NOW);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<BureauPhone>> cap = ArgumentCaptor.forClass(List.class);
        verify(phones).saveAll(cap.capture());
        assertThat(cap.getValue().get(0).isIdentityMismatch()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void requestEchoesAndOwnNumberAreNotIndexed() {
        when(profiles.findMobilesForCustomer(3L)).thenReturn(List.of("9000000001"));
        ApplicationVerification v = new ApplicationVerification();
        v.setApplicationId(7L);
        v.setRawResponse("{\"data\":{\"REQUEST\":{\"PHONE-1\":\"9111111111\"},"
                + "\"PERSONAL-INFO-VARIATION\":{\"PHONE-NUMBER-VARIATIONS\":{\"VARIATION\":["
                + "{\"VALUE\":\"9000000001\",\"REPORTED-DATE\":\"31-07-2026\"},"
                + "{\"VALUE\":\"9333333333\",\"REPORTED-DATE\":\"31-07-2026\"}]}}}}");
        indexer.index(7L, v, NOW);
        ArgumentCaptor<List<BureauPhone>> cap = ArgumentCaptor.forClass(List.class);
        verify(phones).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting(BureauPhone::getMobile).containsExactly("9333333333");
        assertThat(cap.getValue().get(0).getPulledAt()).isEqualTo(NOW);
    }

    @Test
    void insideATransactionIndexingRunsOnlyAfterCommit() {
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(indexer.index(7L, row(null), NOW)).isZero();
            verify(phones, org.mockito.Mockito.never()).deleteByApplicationId(any()); // rolled back = never runs
            org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit);
            verify(phones).deleteByApplicationId(7L);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void failureIsSwallowed() {
        doThrow(new IllegalStateException("db down")).when(phones).deleteByApplicationId(7L);
        assertThat(indexer.index(7L, row(null), NOW)).isZero();
    }

    @Test
    void unparseableRawIsSwallowed() {
        ApplicationVerification v = row(null);
        v.setRawResponse("not json");
        assertThat(indexer.index(7L, v, NOW)).isZero();
    }

    @Test
    void backfillIsAdminOnlyAndIdempotentCounts() {
        ApplicationVerificationRepository verifications = mock(ApplicationVerificationRepository.class);
        ApplicationVerification v = row(null);
        when(verifications.findByCheckTypeAndRawResponseIsNotNull(any(String.class), any(Pageable.class)))
                .thenReturn(new SliceImpl<>(List.of(v)));
        BureauPhoneBackfillService svc = new BureauPhoneBackfillService(verifications, indexer);

        ActorContext.set(new CurrentActor("1", "Ops", "CREDIT_HEAD"));
        assertThatThrownBy(svc::backfill).isInstanceOf(BusinessException.class);

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThat(svc.backfill()).isEqualTo(new BureauPhoneBackfillService.Counts(1, 1));
        assertThat(svc.backfill()).isEqualTo(new BureauPhoneBackfillService.Counts(1, 1)); // same answer twice
    }
}
