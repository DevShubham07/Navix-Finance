package com.navix.loan.bre;

import com.navix.common.entity.BaseAuditEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** An admin override of one BRE number (V104). No row = the {@link BreRuleCatalog} default. */
@Entity
@Table(name = "bre_threshold")
@Getter
@Setter
@NoArgsConstructor
public class BreThreshold extends BaseAuditEntity {

    @Column(name = "threshold_key", nullable = false, length = 64)
    private String thresholdKey;

    @Column(name = "value", nullable = false)
    private long value;
}
