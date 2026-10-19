package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.service.TenantHierarchy;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import com.stackwizard.booking_api.service.platform.PlatformOhipClient;
import com.stackwizard.booking_api.service.platform.PlatformOhipCredentials;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Resolves Opera/OHIP connection settings. Prefers Mikos Platform OHIP integration (chain storage),
 * then falls back to local {@code tenant_integration_config} (PMS/BOOKING + OPERA).
 */
@Component
public class OperaTenantConfigResolver {
    private static final String DEFAULT_OAUTH_PATH = "/oauth/v1/tokens";
    private static final String PROVIDER = "OPERA";
    private static final String INTEGRATION_TYPE_PMS = "PMS";
    private static final String INTEGRATION_TYPE_BOOKING = "BOOKING";

    private final TenantIntegrationConfigService tenantConfigService;
    private final TenantHierarchy hierarchy;
    private final PlatformTenantMappingRepository mappingRepo;
    private final PlatformOhipClient platformOhipClient;

    public OperaTenantConfigResolver(TenantIntegrationConfigService tenantConfigService,
                                     TenantHierarchy hierarchy,
                                     PlatformTenantMappingRepository mappingRepo,
                                     PlatformOhipClient platformOhipClient) {
        this.tenantConfigService = tenantConfigService;
        this.hierarchy = hierarchy;
        this.mappingRepo = mappingRepo;
        this.platformOhipClient = platformOhipClient;
    }

    public OperaResolvedConfig resolve(Long tenantId) {
        Optional<OperaResolvedConfig> fromPlatform = resolveFromPlatform(tenantId);
        if (fromPlatform.isPresent()) {
            return fromPlatform.get();
        }

        TenantIntegrationConfig tenantConfig = findOperaConfig(tenantId)
                .orElseThrow(() -> new IllegalStateException("Opera config is missing for tenant " + tenantId
                        + " (Platform OHIP and local tenant_integration_config)"));

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
        Optional<String> fromMapping = mappingRepo.findByTenantId(tenantId)
                .map(PlatformTenantMapping::getHotelCode)
                .map(this::normalizeNullable)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase);
        if (fromMapping.isPresent()) {
            return fromMapping;
        }
        return findOperaConfig(tenantId)
                .map(TenantIntegrationConfig::getHotelCode)
                .map(this::normalizeNullable)
                .filter(StringUtils::hasText)
                .map(String::toUpperCase);
    }

    private Optional<OperaResolvedConfig> resolveFromPlatform(Long tenantId) {
        UUID platformTenantId = platformTenantIdOf(tenantId);
        if (platformTenantId == null) {
            return Optional.empty();
        }
        Optional<PlatformOhipCredentials> creds = platformOhipClient.fetchAsService(platformTenantId);
        if (creds.isEmpty()) {
            Long orgId = hierarchy.orgIdOf(tenantId);
            if (orgId != null && !Objects.equals(orgId, tenantId)) {
                UUID orgPlatformId = platformTenantIdOf(orgId);
                if (orgPlatformId != null && !orgPlatformId.equals(platformTenantId)) {
                    creds = platformOhipClient.fetchAsService(orgPlatformId);
                }
            }
        }
        return creds.map(this::toResolved);
    }

    private UUID platformTenantIdOf(Long localTenantId) {
        if (localTenantId == null) {
            return null;
        }
        return mappingRepo.findByTenantId(localTenantId)
                .map(PlatformTenantMapping::getPlatformTenantId)
                .orElse(null);
    }

    private OperaResolvedConfig toResolved(PlatformOhipCredentials creds) {
        String baseUrl = requireText(normalizeBaseUrl(creds.hostname()), "hostname");
        String appKey = requireText(normalizeNullable(creds.xAppKey()), "xAppKey");
        String oauthPath = firstNonBlank(normalizeNullable(creds.tokenPath()), DEFAULT_OAUTH_PATH);
        String clientId = requireText(normalizeNullable(creds.clientId()), "clientId");
        String clientSecret = requireText(normalizeNullable(creds.clientSecret()), "clientSecret");
        String enterpriseId = requireText(normalizeNullable(creds.enterpriseId()), "enterpriseId");
        return new OperaResolvedConfig(baseUrl, oauthPath, appKey, clientId, clientSecret, enterpriseId, null);
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

    private String normalizeBaseUrl(String hostname) {
        String host = normalizeNullable(hostname);
        if (host == null) {
            return null;
        }
        if (host.startsWith("http://") || host.startsWith("https://")) {
            return host.replaceAll("/+$", "");
        }
        return "https://" + host.replaceAll("/+$", "");
    }

    private String requireValue(String value, String fieldName, Long tenantId) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Opera " + fieldName + " is missing for tenant " + tenantId);
        }
        return value.trim();
    }

    private String requireText(String value, String fieldName) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Opera " + fieldName + " is missing in Platform OHIP integration");
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
