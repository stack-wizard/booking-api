package com.stackwizard.booking_api.model;

 
import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;



@Entity
@Table(name = "resource_type")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResourceType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(name = "default_time_model")
    private String defaultTimeModel;



    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
