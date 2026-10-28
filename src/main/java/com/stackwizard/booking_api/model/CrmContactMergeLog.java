package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.OffsetDateTime;

@Entity
@Table(name = "crm_contact_merge_log")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmContactMergeLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "from_contact_id", nullable = false)
    private Long fromContactId;

    @Column(name = "into_contact_id", nullable = false)
    private Long intoContactId;

    @Column(name = "reason")
    private String reason;

    @Column(name = "operator_user_id")
    private Long operatorUserId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
