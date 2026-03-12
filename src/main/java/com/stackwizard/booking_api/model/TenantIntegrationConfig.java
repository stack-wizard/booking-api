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

@Entity
@Table(name = "tenant_integration_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantIntegrationConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(name = "integration_type", nullable = false)
    private String integrationType;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "oauth_path")
    private String oauthPath;

    @Column(name = "request_path")
    private String requestPath;

    @Column(name = "hotel_code")
    private String hotelCode;

    @Column(name = "hotel_name")
    private String hotelName;

    @Column(name = "legal_owner")
    private String legalOwner;

    @Column(name = "property_tax_number")
    private String propertyTaxNumber;

    @Column(name = "country_code")
    private String countryCode;

    @Column(name = "country_name")
    private String countryName;

    @Column(name = "application_name")
    private String applicationName;

    @Column(name = "client_id")
    private String clientId;

    @Column(name = "client_secret")
    private String clientSecret;

    @Column(name = "authenticity_token")
    private String authenticityToken;

    @Column(name = "callback_auth_token")
    private String callbackAuthToken;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
