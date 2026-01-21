package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;


import lombok.*;

@Entity
@Table(name = "resource_composition")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceComposition {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_resource_id", nullable = false)
    private Resource parentResource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_resource_id", nullable = false)
    private Resource memberResource;

    @Column(nullable = false)
    private Integer qty;


}
