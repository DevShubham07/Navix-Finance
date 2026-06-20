package com.navix.kyc.entity;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A DigiLocker linking/consent session used to fetch Aadhaar XML.
 * Full Aadhaar number is never stored — only a masked reference.
 * TODO: add masked Aadhaar ref, session URL/token and expiry.
 */
@Entity
@Table(name = "digilocker_session")
@Getter
@Setter
public class DigiLockerSession extends BaseAuditEntity {

    /** FK to the borrower this session belongs to. */
    private Long borrowerId;

    /** DigiLocker client/session identifier. */
    private String clientId;

    /** Session status. TODO: promote to enum (INITIATED/LINKED/EXPIRED/FAILED). */
    private String status;

    private boolean aadhaarLinked;
}
