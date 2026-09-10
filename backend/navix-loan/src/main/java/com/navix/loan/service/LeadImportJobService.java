package com.navix.loan.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.navix.common.exception.BusinessException;
import com.navix.common.security.ActorContext;
import com.navix.loan.dto.LeadDtos.ImportFileRequest;
import com.navix.loan.dto.LeadDtos.ImportIssue;
import com.navix.loan.dto.LeadDtos.ImportJobView;
import com.navix.loan.entity.LeadImportJob;
import com.navix.loan.repository.LeadImportJobRepository;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the lifecycle of a bulk lead import: accept an uploaded file, hand it to
 * {@link LeadImportRunner}, and answer "how is it going".
 *
 * <p><b>Anyone on staff may upload</b>, DSA included — a deliberate product decision. Two guards make
 * that safe and must not be removed:
 * <ol>
 *   <li>imported rows are always unattributed ({@code owner_dsa_id} null, {@code source = "OTHER"},
 *       enforced in {@link LeadImportService}), so a bulk upload can never manufacture DSA
 *       commission; and
 *   <li>{@link #toView} withholds the file's own contact detail from anyone without
 *       {@code customer:view} — a DSA sees counts, never who was already a customer.
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LeadImportJobService {

    private static final List<String> LIVE_STATUSES = List.of(LeadImportJob.QUEUED, LeadImportJob.RUNNING);

    /**
     * Roles allowed to see the per-row detail of an import result. Mirrors {@code customer:view} in
     * the frontend's RBAC map — every staff role except DSA, which is firewalled from customer data.
     */
    private static final List<String> DETAIL_ROLES = List.of(
            "CREDIT_EXECUTIVE", "CREDIT_HEAD", "DISBURSEMENT_HEAD", "ACCOUNTANT",
            "COLLECTION_HEAD", "COLLECTION_EXECUTIVE", "TELECALLER", "ADMIN");

    private final LeadImportJobRepository jobRepository;
    private final LeadImportRunner runner;
    private final ObjectMapper objectMapper;

    /** Accept an uploaded file and queue it. Returns immediately — the work happens off-thread. */
    @Transactional
    public ImportJobView start(ImportFileRequest req) {
        Long staffId = LeadImportService.requireStaffId();
        String role = ActorContext.get().role();

        if (!isSupported(req.fileName())) {
            throw new BusinessException("IMPORT_FORMAT_UNSUPPORTED",
                    "Upload a .csv or .xlsx file — " + req.fileName() + " is neither.");
        }
        // One live import per person. Nine roles each able to queue a 200k-row file would otherwise
        // pile onto a single 1 vCPU / 2 GB task (aws.md: desired count 1).
        if (jobRepository.existsByUploadedByStaffIdAndStatusIn(staffId, LIVE_STATUSES)) {
            throw new BusinessException("IMPORT_ALREADY_RUNNING",
                    "You already have an import running. Wait for it to finish before starting another.");
        }

        LeadImportJob job = new LeadImportJob();
        job.setS3Key(req.s3Key());
        job.setFileName(req.fileName());
        job.setUploadedByStaffId(staffId);
        job.setUploaderRole(role);
        job.setMergeRequested(req.merge());
        job.setStatus(LeadImportJob.QUEUED);
        job.setCreatedAt(Instant.now());
        LeadImportJob saved = jobRepository.save(job);

        runner.run(saved.getId());
        return toView(saved, role);
    }

    @Transactional(readOnly = true)
    public ImportJobView get(Long id) {
        Long staffId = LeadImportService.requireStaffId();
        String role = ActorContext.get().role();
        LeadImportJob job = jobRepository.findById(id)
                .orElseThrow(() -> new BusinessException("NOT_FOUND", "Import not found"));
        // A DSA must not be able to enumerate other people's uploads; staff who can see the lead
        // register may read any import.
        if (!DETAIL_ROLES.contains(role) && !job.getUploadedByStaffId().equals(staffId)) {
            throw new BusinessException("FORBIDDEN_ROLE", "Not your import");
        }
        return toView(job, role);
    }

    @Transactional(readOnly = true)
    public List<ImportJobView> mine() {
        Long staffId = LeadImportService.requireStaffId();
        String role = ActorContext.get().role();
        return jobRepository.findTop20ByUploadedByStaffIdOrderByIdDesc(staffId).stream()
                .map(job -> toView(job, role))
                .toList();
    }

    /**
     * A job left {@code RUNNING} by a restart can never resume — the worker is in-process and the
     * service runs one task, so a deploy mid-import orphans it. Without this it would sit at RUNNING
     * forever and the one-live-job-per-user guard would lock that person out permanently.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void reapInterruptedJobs() {
        List<LeadImportJob> stale = jobRepository.findByStatusIn(LIVE_STATUSES);
        if (stale.isEmpty()) {
            return;
        }
        for (LeadImportJob job : stale) {
            job.setStatus(LeadImportJob.FAILED);
            job.setErrorMessage("Interrupted by a restart — re-upload the file to finish the remaining rows.");
            job.setFinishedAt(Instant.now());
        }
        jobRepository.saveAll(stale);
        log.warn("marked {} lead import job(s) failed after a restart", stale.size());
    }

    private static boolean isSupported(String fileName) {
        String lower = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        return lower.endsWith(".csv") || lower.endsWith(".xlsx") || lower.endsWith(".xlsm");
    }

    /**
     * The issue list names rows of the uploaded file, so it is contact data. Roles without
     * {@code customer:view} — DSA — get the counts and nothing else.
     */
    private ImportJobView toView(LeadImportJob job, String role) {
        List<ImportIssue> issues = DETAIL_ROLES.contains(role) ? readIssues(job.getIssuesJson()) : List.of();
        return new ImportJobView(
                job.getId(),
                job.getStatus(),
                job.getFileName(),
                job.isMergeRequested(),
                job.getTotalRows(),
                job.getProcessedRows(),
                job.getInsertedCount(),
                job.getMergedCount(),
                job.getSkippedDuplicates(),
                job.getSkippedCustomers(),
                job.getIssueCount(),
                issues,
                job.getErrorMessage(),
                job.getStartedAt(),
                job.getFinishedAt(),
                job.getCreatedAt());
    }

    private List<ImportIssue> readIssues(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<ImportIssue>>() {
            });
        } catch (Exception e) {
            log.warn("could not read stored import issues", e);
            return List.of();
        }
    }
}
