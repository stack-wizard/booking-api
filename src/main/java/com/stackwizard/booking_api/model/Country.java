package com.stackwizard.booking_api.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "country")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Country {
    @Id
    @Column(name = "code", length = 2, nullable = false)
    private String code;

    @Column(name = "name", nullable = false, length = 128)
    private String name;
}
