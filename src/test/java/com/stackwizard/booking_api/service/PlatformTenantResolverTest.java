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

import java.util.List;
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

    private static PlatformTenantMapping org(long tenantId, UUID uuid) {
        return PlatformTenantMapping.builder().tenantId(tenantId).platformTenantId(uuid)
                .kind(PlatformTenantMapping.Kind.ORG).status(PlatformTenantMapping.Status.ACTIVE).build();
    }

    private static PlatformTenantMapping property(long tenantId, UUID uuid, long parentId) {
        return PlatformTenantMapping.builder().tenantId(tenantId).platformTenantId(uuid).parentTenantId(parentId)
                .kind(PlatformTenantMapping.Kind.PROPERTY).status(PlatformTenantMapping.Status.ACTIVE).build();
    }

    @Test
    void scopeFromPropertyHeaderUsesParentAsOrg() {
        UUID propertyUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(propertyUuid))
                .thenReturn(Optional.of(property(2L, propertyUuid, 1L)));

        PlatformTenantResolver.TenantScope scope = resolver.resolveScope(propertyUuid, null, true);

        assertThat(scope.orgTenantId()).isEqualTo(1L);
        assertThat(scope.propertyTenantId()).isEqualTo(2L);
    }

    @Test
    void scopeFromOrgWithSelectedChild() {
        UUID orgUuid = UUID.randomUUID();
        UUID propertyUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.of(org(1L, orgUuid)));
        when(mappingRepo.findByPlatformTenantId(propertyUuid))
                .thenReturn(Optional.of(property(3L, propertyUuid, 1L)));

        PlatformTenantResolver.TenantScope scope = resolver.resolveScope(orgUuid, propertyUuid, false);

        assertThat(scope).isEqualTo(new PlatformTenantResolver.TenantScope(1L, 3L));
    }

    @Test
    void scopeRejectsHotelOfAnotherOrganization() {
        UUID orgUuid = UUID.randomUUID();
        UUID foreignUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.of(org(1L, orgUuid)));
        when(mappingRepo.findByPlatformTenantId(foreignUuid))
                .thenReturn(Optional.of(property(7L, foreignUuid, 5L)));

        assertThatThrownBy(() -> resolver.resolveScope(orgUuid, foreignUuid, false))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not a hotel of the selected organization");
    }

    @Test
    void scopeOfOrgWithOneChildDefaultsToIt() {
        UUID orgUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.of(org(1L, orgUuid)));
        when(mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(1L))
                .thenReturn(List.of(property(2L, UUID.randomUUID(), 1L)));

        assertThat(resolver.resolveScope(orgUuid, null, false))
                .isEqualTo(new PlatformTenantResolver.TenantScope(1L, 2L));
    }

    @Test
    void scopeOfOrgWithSeveralChildrenHasNoPropertyUntilSelected() {
        UUID orgUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.of(org(1L, orgUuid)));
        when(mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(1L))
                .thenReturn(List.of(property(2L, UUID.randomUUID(), 1L), property(3L, UUID.randomUUID(), 1L)));

        assertThat(resolver.resolveScope(orgUuid, null, false))
                .isEqualTo(new PlatformTenantResolver.TenantScope(1L, null));
    }

    @Test
    void scopeOfLegacyOrgWithoutChildrenIsItsOwnProperty() {
        UUID orgUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.of(org(4L, orgUuid)));
        when(mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(4L)).thenReturn(List.of());

        assertThat(resolver.resolveScope(orgUuid, null, false))
                .isEqualTo(new PlatformTenantResolver.TenantScope(4L, 4L));
    }

    @Test
    void scopeForUnmappedTenantFailsForM2m() {
        UUID orgUuid = UUID.randomUUID();
        when(mappingRepo.findByPlatformTenantId(orgUuid)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> resolver.resolveScope(orgUuid, null, false))
                .isInstanceOf(ResponseStatusException.class);
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
