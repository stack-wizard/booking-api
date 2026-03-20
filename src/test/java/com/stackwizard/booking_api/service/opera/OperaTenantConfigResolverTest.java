package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperaTenantConfigResolverTest {

    @Mock
    private TenantIntegrationConfigService tenantIntegrationConfigService;

    @InjectMocks
    private OperaTenantConfigResolver resolver;

    @Test
    void resolveUsesOauthConfigWhenStaticAccessTokenIsMissing() {
        TenantIntegrationConfig config = TenantIntegrationConfig.builder()
                .tenantId(1L)
                .integrationType("PMS")
                .provider("OPERA")
                .active(Boolean.TRUE)
                .baseUrl("https://opera.example")
                .oauthPath("/oauth/v1/tokens")
                .appKey("app-key")
                .clientId("client-id")
                .clientSecret("client-secret")
                .enterpriseId("MIKOSE")
                .accessToken("expired-token")
                .build();

        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "PMS", "OPERA"))
                .thenReturn(Optional.of(config));

        OperaTenantConfigResolver.OperaResolvedConfig resolved = resolver.resolve(1L);

        assertThat(resolved.baseUrl()).isEqualTo("https://opera.example");
        assertThat(resolved.oauthPath()).isEqualTo("/oauth/v1/tokens");
        assertThat(resolved.appKey()).isEqualTo("app-key");
        assertThat(resolved.clientId()).isEqualTo("client-id");
        assertThat(resolved.clientSecret()).isEqualTo("client-secret");
        assertThat(resolved.enterpriseId()).isEqualTo("MIKOSE");
        assertThat(resolved.accessToken()).isNull();
    }

    @Test
    void resolveRequiresEnterpriseIdWhenUsingOauthLogin() {
        TenantIntegrationConfig config = TenantIntegrationConfig.builder()
                .tenantId(1L)
                .integrationType("PMS")
                .provider("OPERA")
                .active(Boolean.TRUE)
                .baseUrl("https://opera.example")
                .appKey("app-key")
                .clientId("client-id")
                .clientSecret("client-secret")
                .build();

        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "PMS", "OPERA"))
                .thenReturn(Optional.of(config));

        assertThatThrownBy(() -> resolver.resolve(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enterpriseId");
    }
}
