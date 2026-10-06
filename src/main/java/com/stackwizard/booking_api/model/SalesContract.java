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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "sales_contract")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SalesContract extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "quote_id", nullable = false)
    private Long quoteId;

    @Column(name = "contract_number", nullable = false)
    private String contractNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private String currency;

    @Column(name = "total_amount", nullable = false)
    private BigDecimal totalAmount;

    @Column(columnDefinition = "text")
    private String terms;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "signed_at")
    private OffsetDateTime signedAt;

    @Column(name = "signed_by_name")
    private String signedByName;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    public enum Status {
        DRAFT, SENT, SIGNED, CANCELLED
    }
}
