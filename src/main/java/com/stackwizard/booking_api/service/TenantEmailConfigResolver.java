package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Optional;

@Component
public class TenantEmailConfigResolver {
    private static final String INTEGRATION_TYPE_BOOKING = "BOOKING";
    private static final String PROVIDER = "EMAIL";
    private static final int DEFAULT_SMTP_PORT = 587;
    private static final String DEFAULT_BRAND_NAME = "Booking";
    private static final String DEFAULT_LOCALE = "en";
    private static final String DEFAULT_ARRIVAL_NOTE =
            "Please arrive at the reception desk upon arrival and present this confirmation.";

    private final TenantIntegrationConfigService tenantIntegrationConfigService;

    public TenantEmailConfigResolver(TenantIntegrationConfigService tenantIntegrationConfigService) {
        this.tenantIntegrationConfigService = tenantIntegrationConfigService;
    }

    public Optional<EmailResolvedConfig> findActive(Long tenantId) {
        Optional<TenantIntegrationConfig> config = tenantIntegrationConfigService
                .findByTenantIdAndTypeAndProvider(tenantId, INTEGRATION_TYPE_BOOKING, PROVIDER);
        if (config.isEmpty() || !Boolean.TRUE.equals(config.get().getActive())) {
            return Optional.empty();
        }
        return Optional.of(resolve(config.get(), tenantId));
    }

    private EmailResolvedConfig resolve(TenantIntegrationConfig config, Long tenantId) {
        requireValue(config.getSmtpHost(), "smtpHost", tenantId);
        requireValue(config.getEmailFrom(), "emailFrom", tenantId);
        int smtpPort = config.getSmtpPort() != null ? config.getSmtpPort() : DEFAULT_SMTP_PORT;
        if (smtpPort <= 0) {
            throw new IllegalStateException("Email smtpPort is invalid for tenant " + tenantId);
        }
        boolean smtpAuth = !Boolean.FALSE.equals(config.getSmtpAuth());
        if (smtpAuth) {
            requireValue(config.getSmtpUsername(), "smtpUsername", tenantId);
            requireValue(config.getSmtpPassword(), "smtpPassword", tenantId);
        }

        return new EmailResolvedConfig(
                config.getSmtpHost().trim(),
                smtpPort,
                trimToNull(config.getSmtpUsername()),
                trimToNull(config.getSmtpPassword()),
                smtpAuth,
                Boolean.TRUE.equals(config.getSmtpStarttlsEnabled()),
                Boolean.TRUE.equals(config.getSmtpSslEnabled()),
                config.getEmailFrom().trim(),
                trimToNull(config.getEmailReplyTo()),
                firstNonBlank(config.getEmailBrandName(), config.getHotelName(), DEFAULT_BRAND_NAME),
                trimToNull(config.getEmailSupportEmail()),
                trimToNull(config.getEmailFooterLocation()),
                firstNonBlank(config.getEmailArrivalNote(), DEFAULT_ARRIVAL_NOTE),
                normalizeLocale(config.getEmailLocale())
        );
    }

    private void requireValue(String value, String fieldName, Long tenantId) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Email " + fieldName + " is missing for tenant " + tenantId);
        }
    }

    private String trimToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return null;
    }

    private String normalizeLocale(String rawLocale) {
        if (!StringUtils.hasText(rawLocale)) {
            return DEFAULT_LOCALE;
        }
        Locale locale = Locale.forLanguageTag(rawLocale.trim().replace('_', '-'));
        return StringUtils.hasText(locale.getLanguage()) ? locale.toLanguageTag() : DEFAULT_LOCALE;
    }

    public record EmailResolvedConfig(
            String smtpHost,
            int smtpPort,
            String smtpUsername,
            String smtpPassword,
            boolean smtpAuth,
            boolean smtpStarttlsEnabled,
            boolean smtpSslEnabled,
            String emailFrom,
            String emailReplyTo,
            String brandName,
            String supportEmail,
            String footerLocation,
            String arrivalNote,
            String locale
    ) {
    }
}
