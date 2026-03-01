package com.stackwizard.booking_api.service.payment;

import com.stackwizard.booking_api.model.TenantPaymentProviderConfig;
import com.stackwizard.booking_api.service.TenantPaymentProviderConfigService;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class MonriTenantConfigResolver {
    private final TenantPaymentProviderConfigService tenantConfigService;

    public MonriTenantConfigResolver(TenantPaymentProviderConfigService tenantConfigService) {
        this.tenantConfigService = tenantConfigService;
    }

    public MonriResolvedConfig resolve(Long tenantId) {
        TenantPaymentProviderConfig tenantConfig = tenantConfigService.findByTenantIdAndProvider(tenantId, "MONRI")
                .orElseThrow(() -> new IllegalStateException("Monri config is missing for tenant " + tenantId));
        if (!Boolean.TRUE.equals(tenantConfig.getActive())) {
            throw new IllegalStateException("Monri config is disabled for tenant " + tenantId);
        }
        requireValue(tenantConfig.getBaseUrl(), "baseUrl", tenantId);
        requireValue(tenantConfig.getOauthPath(), "oauthPath", tenantId);
        requireValue(tenantConfig.getPaymentNewPath(), "paymentNewPath", tenantId);
        requireValue(tenantConfig.getClientId(), "clientId", tenantId);
        requireValue(tenantConfig.getClientSecret(), "clientSecret", tenantId);
        requireValue(tenantConfig.getAuthenticityToken(), "authenticityToken", tenantId);

        return new MonriResolvedConfig(
                tenantConfig.getBaseUrl(),
                tenantConfig.getOauthPath(),
                tenantConfig.getPaymentNewPath(),
                tenantConfig.getClientId(),
                tenantConfig.getClientSecret(),
                tenantConfig.getAuthenticityToken(),
                tenantConfig.getCallbackAuthToken()
        );
    }

    private void requireValue(String value, String fieldName, Long tenantId) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException("Monri " + fieldName + " is missing for tenant " + tenantId);
        }
    }

    public record MonriResolvedConfig(
            String baseUrl,
            String oauthPath,
            String paymentNewPath,
            String clientId,
            String clientSecret,
            String authenticityToken,
            String callbackAuthToken
    ) {
    }
}
