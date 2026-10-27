package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.time.OffsetDateTime;

@Entity
@Table(name = "price_profile_date")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PriceProfileDate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "price_profile_id", nullable = false)
    private PriceProfile priceProfile;

    @Column(name = "date_from", nullable = false)
    private LocalDate dateFrom;

    @Column(name = "date_to", nullable = false)
    private LocalDate dateTo;

    @Column
    private String description;

    /** Season the period belongs to (null = not linked; legacy periods). */
    @Column(name = "season_id")
    private Long seasonId;

    @Enumerated(EnumType.STRING)
    @Column(name = "period_kind", nullable = false)
    @Builder.Default
    private PeriodKind periodKind = PeriodKind.SEASON;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /** Precedence inside the same level and channel: DATES before SUB_PERIOD before SEASON. */
    public enum PeriodKind {
        SEASON, SUB_PERIOD, DATES
    }
}
