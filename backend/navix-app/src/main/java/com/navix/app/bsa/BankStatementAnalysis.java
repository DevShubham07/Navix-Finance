package com.navix.app.bsa;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One on-demand Signzy Bank Statement Analysis run for a customer — see V81. */
@Entity
@Table(name = "bank_statement_analysis")
@Getter @Setter @NoArgsConstructor
public class BankStatementAnalysis extends BaseAuditEntity {

    /** Submitted; Signzy is still analysing. */
    public static final String PENDING = "PENDING";
    /** Signzy finished and the report URLs are on file. */
    public static final String ANALYSED = "ANALYSED";
    /** Signzy rejected the submission, or put the analysis on hold. */
    public static final String FAILED = "FAILED";

    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "application_id") private Long applicationId;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "report_id", length = 64) private String reportId;
    @Column(name = "report_status", length = 40) private String reportStatus;
    @Column(name = "report_status_desc", length = 200) private String reportStatusDesc;
    @Column(name = "bank_score", length = 16) private String bankScore;
    @Column(length = 500) private String message;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json", columnDefinition = "jsonb")
    private String responseJson;
    @Column(name = "report_json_url") private String reportJsonUrl;
    @Column(name = "report_excel_url") private String reportExcelUrl;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "last_polled_at") private Instant lastPolledAt;
    @Column(name = "run_by_staff_id", nullable = false) private Long runByStaffId;
    @Column(name = "run_by_name", length = 200) private String runByName;
    @Column(name = "run_by_role", length = 40) private String runByRole;
}
