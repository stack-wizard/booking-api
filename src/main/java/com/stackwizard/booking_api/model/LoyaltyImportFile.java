package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

/** The CSV of one import batch when it is kept in the database instead of S3 (see {@code loyalty.import.storage}). */
@Entity
@Table(name = "loyalty_import_file")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyImportFile {
    @Id
    @Column(name = "batch_id")
    private Long batchId;

    @JdbcTypeCode(SqlTypes.VARBINARY)
    @Column(name = "content", nullable = false)
    private byte[] content;

    @Column(name = "uploaded_at", nullable = false)
    private OffsetDateTime uploadedAt;
}
