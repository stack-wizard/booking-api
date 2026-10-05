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
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;

@Entity
@Table(name = "function_space")
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FunctionSpace extends CrmAuditableEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "resource_id", nullable = false)
    private Long resourceId;

    @Column(name = "area_sqm")
    private BigDecimal areaSqm;

    private String floor;

    @Column(name = "natural_light", nullable = false)
    private Boolean naturalLight;

    @Column(nullable = false)
    private Boolean divisible;

    @Column(name = "min_duration_minutes", nullable = false)
    private Integer minDurationMinutes;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_setup_style")
    private SetupStyle defaultSetupStyle;

    private String description;

    @Column(nullable = false)
    private Boolean active;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "attrs", columnDefinition = "jsonb", nullable = false)
    private JsonNode attrs;

    public enum SetupStyle {
        THEATRE,
        CLASSROOM,
        U_SHAPE,
        BOARDROOM,
        BANQUET,
        CABARET,
        RECEPTION,
        HOLLOW_SQUARE
    }
}
