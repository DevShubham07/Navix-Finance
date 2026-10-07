package com.navix.app.skiptrace;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One on-demand Skip Tracing Lite run for a customer — see V77. */
@Entity
@Table(name = "skip_trace")
@Getter @Setter @NoArgsConstructor
public class SkipTrace extends BaseAuditEntity {

    public static final String SUCCESS = "SUCCESS";
    public static final String NO_RECORD = "NO_RECORD";
    public static final String FAILED = "FAILED";

    @Column(name = "customer_id", nullable = false) private Long customerId;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "loan_id") private Long loanId;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "result_code") private Integer resultCode;
    @Column(name = "provider_request_id", length = 64) private String providerRequestId;
    @Column(length = 500) private String message;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_json", nullable = false, columnDefinition = "jsonb")
    private String requestJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json", columnDefinition = "jsonb")
    private String responseJson;
    @Column(name = "duration_ms") private Long durationMs;
    @Column(name = "run_by_staff_id", nullable = false) private Long runByStaffId;
    @Column(name = "run_by_name", length = 200) private String runByName;
    @Column(name = "run_by_role", length = 40) private String runByRole;
}
