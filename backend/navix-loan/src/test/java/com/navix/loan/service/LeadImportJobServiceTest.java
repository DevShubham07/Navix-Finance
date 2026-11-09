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
    @Mock
    private com.navix.common.storage.DocumentStoragePort storage;

    @AfterEach
    void clear() {
        ActorContext.clear();
    }

    @Test
    void start_refusesAKeyOutsideTheLeadImportFolder() {
        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper(), storage);

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
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper(), storage);

        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));
        var dsaView = service.get(5L);
        assertThat(dsaView.skippedCustomers()).isZero();
        assertThat(dsaView.mergedCount()).isZero();
        assertThat(dsaView.skippedDuplicates()).isEqualTo(9); // one "already known" figure

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        var staffView = service.get(5L);
        assertThat(staffView.skippedCustomers()).isEqualTo(3);
        assertThat(staffView.mergedCount()).isEqualTo(4);
        assertThat(staffView.skippedDuplicates()).isEqualTo(2);
    }

    private LeadImportJob job(long uploader) {
        LeadImportJob job = new LeadImportJob();
        job.setId(8L);
        job.setStatus(LeadImportJob.SUCCEEDED);
        job.setFileName("list.csv");
        job.setS3Key("leads/import/abc-list.csv");
        job.setUploadedByStaffId(uploader);
        job.setCreatedAt(Instant.now());
        return job;
    }

    @Test
    void jobReadAndDownload_uploaderOrAdminOnly() {
        when(jobRepository.findById(8L)).thenReturn(Optional.of(job(4L)));
        when(storage.presignDownload("leads/import/abc-list.csv")).thenReturn("https://s3/get");
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper(), storage);

        ActorContext.set(new CurrentActor("5", "Other", "CREDIT_HEAD"));
        assertThatThrownBy(() -> service.get(8L)).isInstanceOf(BusinessException.class)
                .hasFieldOrPropertyWithValue("code", "NOT_FOUND");
        assertThatThrownBy(() -> service.downloadUrl(8L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);

        ActorContext.set(new CurrentActor("4", "Tara", "TELECALLER"));
        assertThat(service.get(8L).downloadable()).isTrue();
        assertThat(service.downloadUrl(8L)).isEqualTo("https://s3/get");

        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThat(service.downloadUrl(8L)).isEqualTo("https://s3/get");
    }

    @Test
    void aDsaUploaderGetsNoFileAndNoMerge() {
        when(jobRepository.findById(8L)).thenReturn(Optional.of(job(9L)));
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper(), storage);
        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));

        assertThat(service.get(8L).downloadable()).isFalse();
        assertThatThrownBy(() -> service.downloadUrl(8L)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(storage);
    }

    @Test
    void start_forcesMergeOffForADsa() {
        when(jobRepository.existsByUploadedByStaffIdAndStatusIn(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyList())).thenReturn(false);
        when(jobRepository.save(org.mockito.ArgumentMatchers.any(LeadImportJob.class)))
                .thenAnswer(i -> i.getArgument(0));
        var service = new LeadImportJobService(jobRepository, events, new ObjectMapper(), storage);

        ActorContext.set(new CurrentActor("9", "Dee", "DSA"));
        assertThat(service.start(new ImportFileRequest("leads/import/a.csv", "a.csv", true)).merge()).isFalse();
        ActorContext.set(new CurrentActor("1", "Admin", "ADMIN"));
        assertThat(service.start(new ImportFileRequest("leads/import/a.csv", "a.csv", true)).merge()).isTrue();
    }
}
