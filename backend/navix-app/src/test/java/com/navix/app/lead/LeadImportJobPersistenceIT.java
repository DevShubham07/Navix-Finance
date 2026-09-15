package com.navix.app.lead;

import static org.assertj.core.api.Assertions.assertThat;

import com.navix.loan.entity.LeadImportJob;
import com.navix.loan.repository.LeadImportJobRepository;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * {@link LeadImportJob} against a real Flyway-migrated PostgreSQL.
 *
 * <p><b>Why this exists.</b> The bulk lead import shipped with {@code issues_json} declared
 * {@code jsonb} in V68 but mapped as a bare {@code String}, without the
 * {@code @JdbcTypeCode(SqlTypes.JSON)} every other jsonb column in this codebase carries. Hibernate
 * then bound the parameter as {@code varchar}, and PostgreSQL rejects that when the statement is
 * PARSED — on the parameter's declared type, before it looks at the value — so even the {@code null}
 * bind on the very first INSERT failed. Every upload, from every role, answered
 * {@code 500 INTERNAL_ERROR}, and the feature had never worked.
 *
 * <p>Nothing caught it. {@code ddl-auto: validate} passes, because the explicit
 * {@code columnDefinition} makes the validator compare jsonb against jsonb and only the runtime bind
 * differs. {@code LeadImportScaleIT} proved the 200k-row insert into {@code lead} through a
 * hand-built {@code JdbcTemplate} and a table it creates itself; it never touches
 * {@code lead_import_job}. The unit tests mock their repositories. <b>No test anywhere persisted
 * this entity</b> — so this one does, both with the column null and with real JSON in it.
 */
@SpringBootTest
@Testcontainers
@Tag("integration")
class LeadImportJobPersistenceIT {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired LeadImportJobRepository repository;
    @Autowired JdbcTemplate jdbc;

    private static LeadImportJob queued(String fileName) {
        LeadImportJob job = new LeadImportJob();
        job.setS3Key("leads/import/" + fileName);
        job.setFileName(fileName);
        job.setUploadedByStaffId(7L);
        job.setUploaderRole("DSA");
        job.setMergeRequested(true);
        job.setStatus(LeadImportJob.QUEUED);
        job.setCreatedAt(Instant.now());
        return job;
    }

    /**
     * The exact insert {@code LeadImportJobService.start} does, and the one that used to 500: a brand
     * new job has no issues yet, so {@code issues_json} goes in null.
     */
    @Test
    void queuesAJobWithNoIssuesYet() {
        LeadImportJob saved = repository.saveAndFlush(queued("leads.csv"));

        assertThat(saved.getId()).isNotNull();
        assertThat(repository.findById(saved.getId()))
                .get()
                .satisfies(found -> {
                    assertThat(found.getStatus()).isEqualTo(LeadImportJob.QUEUED);
                    assertThat(found.getIssuesJson()).isNull();
                    assertThat(found.getS3Key()).isEqualTo("leads/import/leads.csv");
                });
    }

    /** And the write the worker makes after a chunk with bad rows in it — real JSON, round-tripped. */
    @Test
    void storesAndReadsBackTheIssueList() {
        String issues = "[{\"row\":3,\"field\":\"mobile\",\"message\":\"not a 10-digit number\"}]";

        LeadImportJob job = queued("problems.xlsx");
        job.setIssuesJson(issues);
        job.setIssueCount(1);
        job.setStatus(LeadImportJob.SUCCEEDED);
        Long id = repository.saveAndFlush(job).getId();

        assertThat(repository.findById(id)).get().satisfies(found ->
                assertThat(found.getIssuesJson()).isEqualTo(issues));
        // Stored AS jsonb, not as a quoted string in a text column — the mapping is the point.
        assertThat(jdbc.queryForObject(
                "select jsonb_typeof(issues_json) from lead_import_job where id = ?", String.class, id))
                .isEqualTo("array");
        assertThat(jdbc.queryForObject(
                "select issues_json -> 0 ->> 'field' from lead_import_job where id = ?", String.class, id))
                .isEqualTo("mobile");
    }

    /**
     * An upload that is rejected on arrival is still recorded, so the object the browser has already
     * PUT to S3 is never left with nothing pointing at it. Nothing in the app deletes it —
     * {@code DocumentStoragePort} exposes no delete at all — so the key is how the list is recovered.
     */
    @Test
    void keepsTheS3KeyOnAFailedImport() {
        LeadImportJob job = queued("wrong-format.pdf");
        job.setStatus(LeadImportJob.FAILED);
        job.setErrorMessage("Upload a .csv or .xlsx file — wrong-format.pdf is neither.");
        job.setFinishedAt(Instant.now());
        Long id = repository.saveAndFlush(job).getId();

        assertThat(repository.findById(id)).get().satisfies(found -> {
            assertThat(found.getStatus()).isEqualTo(LeadImportJob.FAILED);
            assertThat(found.getS3Key()).isEqualTo("leads/import/wrong-format.pdf");
        });
    }

    /** The one-live-job-per-user guard and the boot reaper both run this derived query. */
    @Test
    void findsOnlyTheCallersLiveJobs() {
        repository.saveAndFlush(queued("mine-running.csv"));

        LeadImportJob finished = queued("mine-done.csv");
        finished.setStatus(LeadImportJob.SUCCEEDED);
        repository.saveAndFlush(finished);

        List<String> live = List.of(LeadImportJob.QUEUED, LeadImportJob.RUNNING);
        assertThat(repository.existsByUploadedByStaffIdAndStatusIn(7L, live)).isTrue();
        assertThat(repository.existsByUploadedByStaffIdAndStatusIn(8L, live)).isFalse();
    }
}
