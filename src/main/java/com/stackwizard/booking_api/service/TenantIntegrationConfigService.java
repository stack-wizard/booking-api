package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.repository.TenantIntegrationConfigRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Service
public class TenantIntegrationConfigService {
    private static final List<String> ALLOWED_TYPES = List.of("PAYMENT", "BOOKING", "FISCALIZATION", "PMS");

    private final TenantIntegrationConfigRepository repo;

    public TenantIntegrationConfigService(TenantIntegrationConfigRepository repo) {
        this.repo = repo;
    }

    public List<TenantIntegrationConfig> findAll() {
        return repo.findAll();
    }

    public List<TenantIntegrationConfig> findByTenantId(Long tenantId) {
        return repo.findByTenantId(tenantId);
    }

    public Optional<TenantIntegrationConfig> findById(Long id) {
        return repo.findById(id);
    }

    public Optional<TenantIntegrationConfig> findByTenantIdAndTypeAndProvider(Long tenantId,
                                                                               String integrationType,
                                                                               String provider) {
        if (tenantId == null
                || integrationType == null || integrationType.isBlank()
                || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return repo.findByTenantIdAndIntegrationTypeAndProvider(
                tenantId,
                integrationType.trim().toUpperCase(Locale.ROOT),
                provider.trim().toUpperCase(Locale.ROOT)
        );
    }

    public TenantIntegrationConfig save(TenantIntegrationConfig config) {
        if (config.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (config.getIntegrationType() == null || config.getIntegrationType().isBlank()) {
            throw new IllegalArgumentException("integrationType is required");
        }
        if (config.getProvider() == null || config.getProvider().isBlank()) {
            throw new IllegalArgumentException("provider is required");
        }
        String integrationType = config.getIntegrationType().trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPES.contains(integrationType)) {
            throw new IllegalArgumentException("integrationType must be PAYMENT, BOOKING, FISCALIZATION, or PMS");
        }
        config.setIntegrationType(integrationType);
        config.setProvider(config.getProvider().trim().toUpperCase(Locale.ROOT));
        config.setBaseUrl(normalizeNullable(config.getBaseUrl()));
        config.setOauthPath(normalizeNullable(config.getOauthPath()));
        config.setRequestPath(normalizeNullable(config.getRequestPath()));
        config.setHotelCode(normalizeNullableUpper(config.getHotelCode()));
        config.setHotelName(normalizeNullable(config.getHotelName()));
        config.setLegalOwner(normalizeNullable(config.getLegalOwner()));
        config.setPropertyTaxNumber(normalizeNullable(config.getPropertyTaxNumber()));
        config.setCountryCode(normalizeNullableUpper(config.getCountryCode()));
        config.setCountryName(normalizeNullable(config.getCountryName()));
        config.setApplicationName(normalizeNullable(config.getApplicationName()));
        config.setClientId(normalizeNullable(config.getClientId()));
        config.setClientSecret(normalizeNullable(config.getClientSecret()));
        config.setEnterpriseId(normalizeNullable(config.getEnterpriseId()));
        config.setAuthenticityToken(normalizeNullable(config.getAuthenticityToken()));
        config.setCallbackAuthToken(normalizeNullable(config.getCallbackAuthToken()));
        config.setAppKey(normalizeNullable(config.getAppKey()));
        config.setAccessToken(normalizeNullable(config.getAccessToken()));
        config.setSmtpHost(normalizeNullable(config.getSmtpHost()));
        config.setSmtpPort(config.getSmtpPort());
        config.setSmtpUsername(normalizeNullable(config.getSmtpUsername()));
        config.setSmtpPassword(normalizeNullable(config.getSmtpPassword()));
        config.setSmtpAuth(config.getSmtpAuth());
        config.setSmtpStarttlsEnabled(config.getSmtpStarttlsEnabled());
        config.setSmtpSslEnabled(config.getSmtpSslEnabled());
        config.setEmailFrom(normalizeNullable(config.getEmailFrom()));
        config.setEmailReplyTo(normalizeNullable(config.getEmailReplyTo()));
        config.setEmailBrandName(normalizeNullable(config.getEmailBrandName()));
        config.setEmailSupportEmail(normalizeNullable(config.getEmailSupportEmail()));
        config.setEmailFooterLocation(normalizeNullable(config.getEmailFooterLocation()));
        config.setEmailArrivalNote(normalizeNullable(config.getEmailArrivalNote()));
        config.setEmailLocale(normalizeNullableLower(config.getEmailLocale()));
        config.setPublicAccessUrlTemplate(normalizeNullable(config.getPublicAccessUrlTemplate()));
        if (config.getActive() == null) {
            config.setActive(Boolean.TRUE);
        }
        return repo.save(config);
    }

    public void deleteById(Long id) {
        repo.deleteById(id);
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeNullableUpper(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeNullableLower(String value) {
        String normalized = normalizeNullable(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
