package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.TenantConfig;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.TenantConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformTenantResolverTest {

    @Mock
    private PlatformTenantMappingRepository mappingRepo;
    @Mock
    private TenantConfigRepository tenantConfigRepo;
    @Mock
    private AppUserRepository appUserRepo;

    private PlatformTenantResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new PlatformTenantResolver(mappingRepo, tenantConfigRepo, appUserRepo);
    }

    @Test
    void requireExistingFailsWhenUnmapped() {
        UUID platformTenantId = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(platformTenantId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> resolver.requireExisting(platformTenantId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveOrProvisionCreatesMappingConfigAndOnlineUser() {
        UUID platformTenantId = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(platformTenantId)).thenReturn(Optional.empty());
        when(mappingRepo.nextTenantId()).thenReturn(99L);
        when(mappingRepo.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(tenantConfigRepo.findByTenantId(99L)).thenReturn(Optional.empty());
        when(tenantConfigRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(appUserRepo.findByTenantIdAndUsername(99L, "online-system-tenant-99"))
                .thenReturn(Optional.empty());
        when(appUserRepo.findByUsername("online-system-tenant-99")).thenReturn(Optional.empty());
        when(appUserRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Long tenantId = resolver.resolveOrProvision(platformTenantId);

        assertThat(tenantId).isEqualTo(99L);
        verify(mappingRepo).saveAndFlush(any(PlatformTenantMapping.class));
        verify(tenantConfigRepo).save(any(TenantConfig.class));
        verify(appUserRepo).save(any(AppUser.class));
    }

    @Test
    void resolveOrProvisionReturnsExisting() {
        UUID platformTenantId = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(platformTenantId))
                .thenReturn(Optional.of(PlatformTenantMapping.builder()
                        .tenantId(5L)
                        .platformTenantId(platformTenantId)
                        .build()));

        assertThat(resolver.resolveOrProvision(platformTenantId)).isEqualTo(5L);
    }
}
