package com.navix.iam.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.iam.domain.StaffRole;
import com.navix.iam.domain.StaffStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A staff member who logs into the DhanBoost back office. Authentication is a BCrypt
 * {@code passwordHash} (set at invite-accept; null for not-yet-credentialed rows).
 */
@Entity
@Table(name = "staff_user")
@Getter
@Setter
@NoArgsConstructor
public class StaffUser extends BaseAuditEntity {

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffStatus status;

    /** BCrypt hash; null until the staffer sets a password (invite-accept). */
    @Column(name = "password_hash")
    private String passwordHash;

    /** Self-editable org fields (Phase 2.2); not tied to role/permissions. */
    @Column(name = "department", length = 120)
    private String department;

    @Column(name = "designation", length = 120)
    private String designation;

    /** Contact mobile — the second factor for the email+mobile forgot-password gate (V34). */
    @Column(name = "mobile", length = 20)
    private String mobile;

    /**
     * The one live console session allowed per staffer (V54, all roles including ADMIN). The staff
     * JWT's {@code sid} claim must match this value to be treated as current — see
     * {@code AuthController.staffLogin} (mints it) and {@code StaffSessionRegistry} (checks it on
     * every request). Null = no live session (never logged in, or cleanly logged out).
     */
    @Column(name = "active_session_id", length = 64)
    private String activeSessionId;

    @Column(name = "active_session_at")
    private java.time.Instant activeSessionAt;
}
