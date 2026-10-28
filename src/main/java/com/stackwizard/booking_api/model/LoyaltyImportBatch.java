package com.stackwizard.booking_api.model;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "loyalty_import_batch")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyImportBatch {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Enumerated(EnumType.STRING)
    @Column(name = "import_type", nullable = false)
    private ImportType importType;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Column(name = "s3_key", nullable = false)
    private String s3Key;

    @Column(name = "file_name", nullable = false)
    private String fileName;

    @Column(name = "file_size")
    private Long fileSize;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "column_mapping", nullable = false, columnDefinition = "jsonb")
    private JsonNode columnMapping;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, columnDefinition = "jsonb")
    private JsonNode options;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private Status status;

    @Column(name = "rows_total", nullable = false)
    private Integer rowsTotal;

    @Column(name = "rows_valid", nullable = false)
    private Integer rowsValid;

    @Column(name = "rows_invalid", nullable = false)
    private Integer rowsInvalid;

    @Column(name = "rows_applied", nullable = false)
    private Integer rowsApplied;

    @Column(name = "last_applied_row", nullable = false)
    private Integer lastAppliedRow;

    @Column(name = "lease_owner")
    private String leaseOwner;

    @Column(name = "lease_until")
    private OffsetDateTime leaseUntil;

    @Column(name = "error")
    private String error;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    public enum ImportType {
        MEMBERS, RESERVATIONS
    }

    public enum Status {
        UPLOADED, QUEUED, STAGING, VALIDATED, APPLY_QUEUED, APPLYING, DONE, FAILED, CANCELLED
    }
}
