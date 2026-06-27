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
 * A staff member who logs into the NAVIX back office. Authentication is a BCrypt
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
}
