package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.service.TenantHierarchy;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;

@Component
public class OperaTenantConfigResolver {
    private static final String DEFAULT_OAUTH_PATH = "/oauth/v1/tokens";
    private static final String PROVIDER = "OPERA";
    private static final String INTEGRATION_TYPE_PMS = "PMS";
    private static final String INTEGRATION_TYPE_BOOKING = "BOOKING";

    private final TenantIntegrationConfigService tenantConfigService;
    private final TenantHierarchy hierarchy;

    public OperaTenantConfigResolver(TenantIntegrationConfigService tenantConfigService,
                                     TenantHierarchy hierarchy) {
        this.tenantConfigService = tenantConfigService;
        this.hierarchy = hierarchy;
    }

    public OperaResolvedConfig resolve(Long tenantId) {
        TenantIntegrationConfig tenantConfig = findOperaConfig(tenantId)
                .orElseThrow(() -> new IllegalStateException("Opera config is missing for tenant " + tenantId
                        + " (and its organization)"));

        if (!Boolean.TRUE.equals(tenantConfig.getActive())) {
            throw new IllegalStateException("Opera config is disabled for tenant " + tenantConfig.getTenantId());
        }
        Long configTenantId = tenantConfig.getTenantId();
        String baseUrl = requireValue(tenantConfig.getBaseUrl(), "baseUrl", configTenantId);
        String appKey = requireValue(tenantConfig.getAppKey(), "appKey", configTenantId);
        String oauthPath = firstNonBlank(normalizeNullable(tenantConfig.getOauthPath()), DEFAULT_OAUTH_PATH);
        String clientId = normalizeNullable(tenantConfig.getClientId());
        String clientSecret = normalizeNullable(tenantConfig.getClientSecret());
        String enterpriseId = normalizeNullable(tenantConfig.getEnterpriseId());

        requireValue(clientId, "clientId", configTenantId);
        requireValue(clientSecret, "clientSecret", configTenantId);
        requireValue(enterpriseId, "enterpriseId", configTenantId);

        return new OperaResolvedConfig(
                baseUrl,
                oauthPath,
                appKey,
                clientId,
                clientSecret,
                enterpriseId,
                null
        );
    }

    public Optional<String> findDefaultHotelCode(Long tenantId) {
        return findOperaConfig(tenantId)
                .map(TenantIntegrationConfig::getHotelCode)
                .map(this::normalizeNullable)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase);
    }

    /**
     * OPERA credentials are usually stored once on the chain (organization). A hotel looks there when it has
     * no row of its own.
     */
    private Optional<TenantIntegrationConfig> findOperaConfig(Long tenantId) {
        Optional<TenantIntegrationConfig> own = findOnTenant(tenantId);
        if (own.isPresent()) {
            return own;
        }
        Long orgId = hierarchy.orgIdOf(tenantId);
        if (orgId == null || Objects.equals(orgId, tenantId)) {
            return Optional.empty();
        }
        return findOnTenant(orgId);
    }

    private Optional<TenantIntegrationConfig> findOnTenant(Long tenantId) {
        if (tenantId == null) {
            return Optional.empty();
        }
        return tenantConfigService
                .findByTenantIdAndTypeAndProvider(tenantId, INTEGRATION_TYPE_PMS, PROVIDER)
                .or(() -> tenantConfigService.findByTenantIdAndTypeAndProvider(tenantId, INTEGRATION_TYPE_BOOKING, PROVIDER));
    }

    private String requireValue(String value, String fieldName, Long tenantId) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Opera " + fieldName + " is missing for tenant " + tenantId);
        }
        return value.trim();
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    public record OperaResolvedConfig(
            String baseUrl,
            String oauthPath,
            String appKey,
            String clientId,
            String clientSecret,
            String enterpriseId,
            String accessToken
    ) {
    }
}
