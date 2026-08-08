package com.navix.app.provider;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;

@Entity
@Table(name = "provider_api_execution")
@Getter @Setter @NoArgsConstructor
public class ProviderApiExecution extends BaseAuditEntity {
    @Column(nullable = false, length = 40) private String operation;
    @Column(nullable = false, length = 40) private String provider;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "request_json", nullable = false, columnDefinition = "jsonb") private String requestJson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "response_json", columnDefinition = "jsonb") private String responseJson;
    @Column(nullable = false, length = 20) private String status;
    @Column(name = "error_message", length = 2000) private String errorMessage;
    @Column(name = "duration_ms", nullable = false) private Long durationMs;
    @Column(name = "application_id") private Long applicationId;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
}
