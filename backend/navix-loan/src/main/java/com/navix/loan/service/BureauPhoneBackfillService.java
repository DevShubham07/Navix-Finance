package com.navix.loan.service;

import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.entity.ApplicationVerification;
import com.navix.loan.repository.ApplicationVerificationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

/** ADMIN-only: (re)builds {@code bureau_phone} from every stored BUREAU report. Idempotent (rows are replaced per application). */
@Service
@RequiredArgsConstructor
public class BureauPhoneBackfillService {

    static final int BATCH = 200;

    public record Counts(int applications, int phones) {}

    private final ApplicationVerificationRepository verifications;
    private final BureauPhoneIndexer indexer;

    public Counts backfill() {
        CurrentActor actor = ActorContext.get();
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new BusinessException("FORBIDDEN_ROLE", "ADMIN required");
        }
        int apps = 0;
        int phones = 0;
        int page = 0;
        Slice<ApplicationVerification> slice;
        do {
            slice = verifications.findByCheckTypeAndRawResponseIsNotNull(ApplicationVerificationService.BUREAU,
                    org.springframework.data.domain.PageRequest.of(page++, BATCH, Sort.by("id")));
            for (ApplicationVerification v : slice.getContent()) {
                apps++;
                phones += indexer.index(v.getApplicationId(), v,
                        v.getUpdatedAt() != null ? v.getUpdatedAt() : v.getCreatedAt());
            }
        } while (slice.hasNext());
        return new Counts(apps, phones);
    }
}
