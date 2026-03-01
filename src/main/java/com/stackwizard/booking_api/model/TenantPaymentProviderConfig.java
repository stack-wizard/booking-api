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
@Table(name = "tenant_payment_provider_config")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantPaymentProviderConfig {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Column(nullable = false)
    private String provider;

    @Column(nullable = false)
    private Boolean active;

    @Column(name = "base_url")
    private String baseUrl;

    @Column(name = "oauth_path")
    private String oauthPath;

    @Column(name = "payment_new_path")
    private String paymentNewPath;

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
