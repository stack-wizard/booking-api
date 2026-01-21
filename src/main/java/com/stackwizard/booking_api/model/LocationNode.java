package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;



@Entity
@Table(name = "location_node")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LocationNode {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private LocationNode parent;

    @Column(name = "node_type", nullable = false)
    private String nodeType;

    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder;



    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
