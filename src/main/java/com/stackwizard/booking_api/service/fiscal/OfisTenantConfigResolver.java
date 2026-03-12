package com.stackwizard.booking_api.service.fiscal;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class OfisTenantConfigResolver {
    private static final String PROVIDER = "OFIS";
    private static final String INTEGRATION_TYPE_FISCALIZATION = "FISCALIZATION";

    private final TenantIntegrationConfigService tenantConfigService;

    public OfisTenantConfigResolver(TenantIntegrationConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    public OfisResolvedConfig resolve(Long tenantId) {
        TenantIntegrationConfig tenantConfig = tenantConfigService
                .findByTenantIdAndTypeAndProvider(tenantId, INTEGRATION_TYPE_FISCALIZATION, PROVIDER)
                .orElseThrow(() -> new IllegalStateException("OFIS config is missing for tenant " + tenantId));
        if (!Boolean.TRUE.equals(tenantConfig.getActive())) {
            throw new IllegalStateException("OFIS config is disabled for tenant " + tenantId);
        }
        requireValue(tenantConfig.getBaseUrl(), "baseUrl", tenantId);
        requireValue(tenantConfig.getRequestPath(), "requestPath", tenantId);
        requireValue(tenantConfig.getClientId(), "clientId", tenantId);
        requireValue(tenantConfig.getClientSecret(), "clientSecret", tenantId);

        return new OfisResolvedConfig(
                tenantConfig.getBaseUrl(),
                tenantConfig.getRequestPath(),
                tenantConfig.getClientId(),
                tenantConfig.getClientSecret()
        );
    }

    private void requireValue(String value, String fieldName, Long tenantId) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("OFIS " + fieldName + " is missing for tenant " + tenantId);
        }
    }

    public record OfisResolvedConfig(
            String baseUrl,
            String fiscalizationPath,
            String username,
            String password
    ) {
    }
}
