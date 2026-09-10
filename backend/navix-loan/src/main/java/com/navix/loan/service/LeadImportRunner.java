package com.navix.loan.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.storage.DocumentStoragePort;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportRow;
import com.navix.loan.entity.LeadImportJob;
import com.navix.loan.repository.LeadImportJobRepository;
import com.navix.loan.service.LeadImportService.ImportRun;
import com.navix.loan.service.LeadImportService.NumberedRow;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Runs one import off the request thread.
 *
 * <p>A separate bean from {@link LeadImportJobService} on purpose: {@code @Async} is proxy-based, so
 * a self-invocation from the service that creates the job would run inline on the request thread and
 * silently reintroduce every timeout this design exists to avoid.
 *
 * <p>The worker never reads {@code ActorContext} — that is a request-scoped ThreadLocal and this is
 * not a request thread. Everything it needs (who uploaded, which file, merge or not) comes off the
 * job row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LeadImportRunner {

    private final LeadImportJobRepository jobRepository;
    private final LeadImportService importService;
    private final DocumentStoragePort storage;
    private final ObjectMapper objectMapper;

    @Async("leadImportExecutor")
    public void run(Long jobId) {
        LeadImportJob job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("lead import job {} vanished before it started", jobId);
            return;
        }

        job.setStatus(LeadImportJob.RUNNING);
        job.setStartedAt(Instant.now());
        jobRepository.save(job);

        ImportRun run = new ImportRun(
                job.getUploadedByStaffId(),
                LeadImportService.sourceDetailFor(job.getFileName()),
                job.isMergeRequested());

        try {
            // fetch() pulls the whole object into a byte[]: a 200k-row list is ~12 MB of CSV or
            // ~15 MB of xlsx, which the task can hold. What it must NOT do is materialise the
            // PARSED rows — that was the 200-300 MB the old path spent — so the parser streams from
            // here and nothing downstream keeps more than one chunk.
            byte[] bytes = storage.fetch(job.getS3Key());
            ChunkBuffer buffer = new ChunkBuffer(job, run);
            LeadFileParser.parse(new ByteArrayInputStream(bytes), job.getFileName(), buffer);
            buffer.flush();

            job.setTotalRows(run.getProcessedRows());
            applyProgress(job, run);
            job.setStatus(LeadImportJob.SUCCEEDED);
            job.setFinishedAt(Instant.now());
            jobRepository.save(job);
            log.info("lead import job {} finished: {} inserted, {} merged, {} duplicate, {} customers, {} issues",
                    jobId, run.getInsertedCount(), run.getMergedCount(), run.getSkippedDuplicates(),
                    run.getSkippedCustomers(), run.getIssueCount());
        } catch (BusinessException e) {
            fail(job, run, e.getMessage());
        } catch (RuntimeException e) {
            log.error("lead import job {} failed", jobId, e);
            fail(job, run, "The import stopped unexpectedly: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Rows already committed by earlier chunks stay committed — the counters are saved alongside the
     * failure so the operator can see how far it got, and re-uploading the same file skips them.
     */
    private void fail(LeadImportJob job, ImportRun run, String message) {
        applyProgress(job, run);
        job.setStatus(LeadImportJob.FAILED);
        job.setErrorMessage(truncate(message));
        job.setFinishedAt(Instant.now());
        jobRepository.save(job);
    }

    private void applyProgress(LeadImportJob job, ImportRun run) {
        job.setProcessedRows(run.getProcessedRows());
        job.setInsertedCount(run.getInsertedCount());
        job.setMergedCount(run.getMergedCount());
        job.setSkippedDuplicates(run.getSkippedDuplicates());
        job.setSkippedCustomers(run.getSkippedCustomers());
        job.setIssueCount(run.getIssueCount());
        job.setIssuesJson(writeIssues(run.getIssues()));
    }

    private String writeIssues(List<ImportIssue> issues) {
        if (issues.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (Exception e) {
            log.warn("could not serialise import issues", e);
            return null;
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > 2000 ? message.substring(0, 2000) : message;
    }

    /**
     * Collects parsed rows into chunks and hands each to the service. The buffer is the only thing
     * that grows, and it is bounded by {@link LeadImportService#CHUNK_ROWS}.
     */
    private final class ChunkBuffer implements LeadFileParser.Sink {

        private final LeadImportJob job;
        private final ImportRun run;
        private final List<NumberedRow> rows = new ArrayList<>(LeadImportService.CHUNK_ROWS);

        private ChunkBuffer(LeadImportJob job, ImportRun run) {
            this.job = job;
            this.run = run;
        }

        @Override
        public void row(int rowNumber, ImportRow row) {
            rows.add(new NumberedRow(rowNumber, row));
            if (rows.size() >= LeadImportService.CHUNK_ROWS) {
                flush();
            }
        }

        @Override
        public void issue(ImportIssue issue) {
            run.recordParseIssue(issue);
        }

        void flush() {
            if (rows.isEmpty()) {
                return;
            }
            importService.processChunk(List.copyOf(rows), run);
            rows.clear();
            // Progress after every chunk, in its own transaction: it drives the operator's progress
            // bar on a run that lasts minutes, and it is what tells the boot reaper how far a job
            // killed by a deploy actually got.
            applyProgress(job, run);
            jobRepository.save(job);
        }
    }
}
