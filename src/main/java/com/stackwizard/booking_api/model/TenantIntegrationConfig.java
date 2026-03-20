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

    @Column(name = "enterprise_id")
    private String enterpriseId;

    @Column(name = "authenticity_token")
    private String authenticityToken;

    @Column(name = "callback_auth_token")
    private String callbackAuthToken;

    @Column(name = "app_key")
    private String appKey;

    @Column(name = "access_token")
    private String accessToken;

    @Column(name = "smtp_host")
    private String smtpHost;

    @Column(name = "smtp_port")
    private Integer smtpPort;

    @Column(name = "smtp_username")
    private String smtpUsername;

    @Column(name = "smtp_password")
    private String smtpPassword;

    @Column(name = "smtp_auth")
    private Boolean smtpAuth;

    @Column(name = "smtp_starttls_enabled")
    private Boolean smtpStarttlsEnabled;

    @Column(name = "smtp_ssl_enabled")
    private Boolean smtpSslEnabled;

    @Column(name = "email_from")
    private String emailFrom;

    @Column(name = "email_reply_to")
    private String emailReplyTo;

    @Column(name = "email_brand_name")
    private String emailBrandName;

    @Column(name = "email_support_email")
    private String emailSupportEmail;

    @Column(name = "email_footer_location")
    private String emailFooterLocation;

    @Column(name = "email_arrival_note")
    private String emailArrivalNote;

    @Column(name = "email_locale")
    private String emailLocale;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private OffsetDateTime createdAt;
}
