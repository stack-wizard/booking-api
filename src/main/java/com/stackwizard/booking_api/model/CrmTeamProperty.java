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

/** A hotel the team works for; a team without rows covers every hotel of the chain. */
@Entity
@Table(name = "crm_team_property")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CrmTeamProperty {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "team_id", nullable = false)
    private Long teamId;

    @Column(name = "property_tenant_id", nullable = false)
    private Long propertyTenantId;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
