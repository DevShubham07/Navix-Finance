package com.navix.loan.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * One bulk lead-import run (V68).
 *
 * <p>The import outlives the request that starts it — the file goes browser -> S3, the POST only
 * hands over the key, and an async worker streams it — so its state has to live somewhere the UI can
 * poll and a restart cannot lose. Progress is written after every chunk, which is also what lets the
 * boot-time reaper tell "still running" from "killed by a deploy".
 */
@Getter
@Setter
@Entity
@Table(name = "lead_import_job")
public class LeadImportJob extends BaseAuditEntity {

    public static final String QUEUED = "QUEUED";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED = "FAILED";

    @Column(name = "s3_key", nullable = false, length = 512)
    private String s3Key;

    @Column(name = "file_name", nullable = false, length = 200)
    private String fileName;

    @Column(name = "status", nullable = false, length = 16)
    private String status = QUEUED;

    @Column(name = "uploaded_by_staff_id", nullable = false)
    private Long uploadedByStaffId;

    /** Kept for audit: which role bulk-loaded this list, DSA included. */
    @Column(name = "uploader_role", nullable = false, length = 32)
    private String uploaderRole;

    @Column(name = "merge_requested", nullable = false)
    private boolean mergeRequested;

    /** Null until the file has been read to the end — the row count is not known up front. */
    @Column(name = "total_rows")
    private Integer totalRows;

    @Column(name = "processed_rows", nullable = false)
    private int processedRows;

    @Column(name = "inserted_count", nullable = false)
    private int insertedCount;

    @Column(name = "merged_count", nullable = false)
    private int mergedCount;

    @Column(name = "skipped_duplicates", nullable = false)
    private int skippedDuplicates;

    @Column(name = "skipped_customers", nullable = false)
    private int skippedCustomers;

    @Column(name = "issue_count", nullable = false)
    private int issueCount;

    /** The first {@code MAX_ISSUES} bad rows as JSON. Capped so a wholly-invalid file stays small. */
    @Column(name = "issues_json", columnDefinition = "jsonb")
    private String issuesJson;

    @Column(name = "error_message", length = 2000)
    private String errorMessage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    public boolean isTerminal() {
        return SUCCEEDED.equals(status) || FAILED.equals(status);
    }
}
