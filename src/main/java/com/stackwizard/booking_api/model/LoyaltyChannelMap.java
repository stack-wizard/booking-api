package com.stackwizard.booking_api.model;

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
import java.time.OffsetDateTime;

@Entity
@Table(name = "loyalty_channel_map")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoyaltyChannelMap {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "program_id", nullable = false)
    private Long programId;

    @Column(name = "property_tenant_id")
    private Long propertyTenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "code_type", nullable = false)
    private CodeType codeType;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "qualifying", nullable = false)
    private Boolean qualifying;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    public enum CodeType {
        SOURCE, MARKET, ORIGIN, CHANNEL
    }
}
