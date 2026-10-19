package com.stackwizard.booking_api.service.opera;

import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.service.TenantHierarchy;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import com.stackwizard.booking_api.service.platform.PlatformOhipClient;
import com.stackwizard.booking_api.service.platform.PlatformOhipCredentials;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperaTenantConfigResolverTest {

    private static final UUID DH_PLATFORM_ID = UUID.fromString("21111111-1111-1111-1111-111111111111");

    @Mock
    private TenantIntegrationConfigService tenantIntegrationConfigService;
    @Mock
    private TenantHierarchy hierarchy;
    @Mock
    private PlatformTenantMappingRepository mappingRepo;
    @Mock
    private PlatformOhipClient platformOhipClient;

    @InjectMocks
    private OperaTenantConfigResolver resolver;

    @Test
    void resolvePrefersPlatformOhipOverLocalConfig() {
        when(mappingRepo.findByTenantId(2L)).thenReturn(Optional.of(PlatformTenantMapping.builder()
                .tenantId(2L)
                .platformTenantId(DH_PLATFORM_ID)
                .hotelCode("DH")
                .build()));
        when(platformOhipClient.fetchAsService(DH_PLATFORM_ID)).thenReturn(Optional.of(
                new PlatformOhipCredentials(
                        "opera.example.com",
                        "/oauth/v1/tokens",
                        "app-key",
                        "client-id",
                        "client-secret",
                        "MIKOSE",
                        "scope",
                        null,
                        null)));

        OperaTenantConfigResolver.OperaResolvedConfig resolved = resolver.resolve(2L);

        assertThat(resolved.baseUrl()).isEqualTo("https://opera.example.com");
        assertThat(resolved.oauthPath()).isEqualTo("/oauth/v1/tokens");
        assertThat(resolved.appKey()).isEqualTo("app-key");
        assertThat(resolved.clientId()).isEqualTo("client-id");
        assertThat(resolved.clientSecret()).isEqualTo("client-secret");
        assertThat(resolved.enterpriseId()).isEqualTo("MIKOSE");
    }

    @Test
    void resolveUsesOauthConfigWhenStaticAccessTokenIsMissing() {
        when(mappingRepo.findByTenantId(1L)).thenReturn(Optional.empty());

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
    void resolveFallsBackToOrganizationWhenPropertyHasNoConfig() {
        when(mappingRepo.findByTenantId(2L)).thenReturn(Optional.empty());

        TenantIntegrationConfig orgConfig = TenantIntegrationConfig.builder()
                .tenantId(1L)
                .integrationType("PMS")
                .provider("OPERA")
                .active(Boolean.TRUE)
                .baseUrl("https://opera.example")
                .appKey("app-key")
                .clientId("client-id")
                .clientSecret("client-secret")
                .enterpriseId("MIKOSE")
                .build();

        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(2L, "PMS", "OPERA"))
                .thenReturn(Optional.empty());
        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(2L, "BOOKING", "OPERA"))
                .thenReturn(Optional.empty());
        when(hierarchy.orgIdOf(2L)).thenReturn(1L);
        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "PMS", "OPERA"))
                .thenReturn(Optional.of(orgConfig));

        OperaTenantConfigResolver.OperaResolvedConfig resolved = resolver.resolve(2L);

        assertThat(resolved.baseUrl()).isEqualTo("https://opera.example");
        assertThat(resolved.enterpriseId()).isEqualTo("MIKOSE");
    }

    @Test
    void resolveRequiresEnterpriseIdWhenUsingOauthLogin() {
        when(mappingRepo.findByTenantId(1L)).thenReturn(Optional.empty());

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

    @Test
    void findDefaultHotelCodeUsesPlatformMapping() {
        when(mappingRepo.findByTenantId(2L)).thenReturn(Optional.of(PlatformTenantMapping.builder()
                .tenantId(2L)
                .platformTenantId(DH_PLATFORM_ID)
                .hotelCode("dh")
                .build()));

        assertThat(resolver.findDefaultHotelCode(2L)).contains("DH");
    }
}
