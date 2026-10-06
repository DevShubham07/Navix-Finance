package com.navix.loan.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.common.security.CurrentActor;
import com.navix.loan.dto.LeadDtos.ImportFileRequest;
import com.navix.loan.entity.LeadImportJob;
import com.navix.loan.repository.LeadImportJobRepository;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

/** The runner fetches the job's key server-side, so only a lead-import upload may be queued. */
@ExtendWith(MockitoExtension.class)
class LeadImportJobServiceTest {

    @Mock
    private LeadImportJobRepository jobRepository;
    @Mock
    private ApplicationEventPublisher events;

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void start_refusesAKeyOutsideTheLeadImportFolder() {
        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper());

        for (String key : new String[] {"applications/7/aadhaar_card_front/1.jpg", "leads/import/../kyc/x.csv"}) {
            assertThatThrownBy(() -> service.start(new ImportFileRequest(key, "list.csv", false)))
                    .isInstanceOf(BusinessException.class)
                    .hasFieldOrPropertyWithValue("code", "INVALID_IMPORT_KEY");
        }
        verifyNoInteractions(jobRepository, events);
    }

    @Test
    void aDsaSeesOneAlreadyKnownCount_staffStillSeeTheSplit() {
        LeadImportJob job = new LeadImportJob();
        job.setId(5L);
        job.setStatus(LeadImportJob.SUCCEEDED);
        job.setFileName("list.csv");
        job.setUploadedByStaffId(9L);
        job.setSkippedDuplicates(2);
        job.setSkippedCustomers(3);
        job.setMergedCount(4);
        job.setCreatedAt(Instant.now());
        when(jobRepository.findById(5L)).thenReturn(Optional.of(job));
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper());

        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));
        var dsaView = service.get(5L);
        assertThat(dsaView.skippedCustomers()).isZero();
        assertThat(dsaView.mergedCount()).isZero();
        assertThat(dsaView.skippedDuplicates()).isEqualTo(9); // one "already known" figure

        ActorContext.set(new CurrentActor("4", "Tara", "TELECALLER"));
        var staffView = service.get(5L);
        assertThat(staffView.skippedCustomers()).isEqualTo(3);
        assertThat(staffView.mergedCount()).isEqualTo(4);
        assertThat(staffView.skippedDuplicates()).isEqualTo(2);
    }
}
