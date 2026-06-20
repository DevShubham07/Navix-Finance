package com.navix.iam.entity;

import com.navix.common.entity.BaseAuditEntity;
import com.navix.iam.domain.StaffRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A one-time invitation issued by an Admin so a new staff member can activate
 * their account via a tokenized link.
 * TODO: enforce single-use semantics and expiry validation in InviteService.
 */
@Entity
@Table(name = "staff_invite")
@Getter
@Setter
@NoArgsConstructor
public class StaffInvite extends BaseAuditEntity {

    @Column(nullable = false)
    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StaffRole role;

    @Column(nullable = false, unique = true)
    private String token;

    @Column(nullable = false)
    private Instant expiresAt;

    @Column
    private Instant acceptedAt;
}
