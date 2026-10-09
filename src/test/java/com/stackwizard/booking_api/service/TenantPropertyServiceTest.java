package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.TenantOrganizationDto;
import com.stackwizard.booking_api.dto.TenantPropertyDto;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantPropertyServiceTest {
    static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DH = UUID.fromString("21111111-1111-1111-1111-111111111111");
    static final UUID AA = UUID.fromString("31111111-1111-1111-1111-111111111111");
    static final UUID OTHER = UUID.fromString("41111111-1111-1111-1111-111111111111");

    PlatformTenantMappingRepository repo = mock(PlatformTenantMappingRepository.class);
    TenantPropertyService service = new TenantPropertyService(repo);

    PlatformTenantMapping org = mapping(1L, ORG, PlatformTenantMapping.Kind.ORG, null, null);
    PlatformTenantMapping dh = mapping(2L, DH, PlatformTenantMapping.Kind.PROPERTY, 1L, "DH");
    PlatformTenantMapping aa = mapping(3L, AA, PlatformTenantMapping.Kind.PROPERTY, 1L, "AA");

    @BeforeEach
    void setUp() {
        TenantContext.setScope(1L, null);
        when(repo.findByTenantId(1L)).thenReturn(Optional.of(org));
        when(repo.findByPlatformTenantId(ORG)).thenReturn(Optional.of(org));
        when(repo.findByPlatformTenantId(DH)).thenReturn(Optional.of(dh));
        when(repo.findByPlatformTenantId(AA)).thenReturn(Optional.of(aa));
        when(repo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(1L)).thenReturn(List.of(aa, dh));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void organizationMembershipGrantsEveryHotel() {
        List<TenantPropertyDto> hotels = service.listAccessible(jwt(ORG));

        assertThat(hotels).extracting(TenantPropertyDto::hotelCode).containsExactly("AA", "DH");
    }

    @Test
    void hotelMembershipGrantsOnlyThatHotel() {
        List<TenantPropertyDto> hotels = service.listAccessible(jwt(DH));

        assertThat(hotels).extracting(TenantPropertyDto::hotelCode).containsExactly("DH");
    }

    @Test
    void membershipOnTwoHotelsOfTheSameChainListsTheChainOnce() {
        List<TenantOrganizationDto> orgs = service.listOrganizations(jwt(DH, AA));

        assertThat(orgs).hasSize(1);
        assertThat(orgs.get(0).platformTenantId()).isEqualTo(ORG);
        assertThat(orgs.get(0).propertyPlatformTenantIds()).containsExactly(AA, DH);
        assertThat(orgs.get(0).onboarded()).isTrue();
    }

    @Test
    void unmappedMembershipIsListedAsNotOnboarded() {
        when(repo.findByPlatformTenantId(OTHER)).thenReturn(Optional.empty());

        List<TenantOrganizationDto> orgs = service.listOrganizations(jwt(ORG, OTHER));

        assertThat(orgs).extracting(TenantOrganizationDto::platformTenantId).containsExactly(ORG, OTHER);
        assertThat(orgs.get(1).onboarded()).isFalse();
        assertThat(orgs.get(1).tenantId()).isNull();
    }

    @Test
    void organizationStillInSetupIsNotOnboarded() {
        org.setStatus(PlatformTenantMapping.Status.SETUP);

        assertThat(service.listOrganizations(jwt(ORG)).get(0).onboarded()).isFalse();
    }

    private static PlatformTenantMapping mapping(Long tenantId, UUID platformId, PlatformTenantMapping.Kind kind,
                                                 Long parent, String code) {
        return PlatformTenantMapping.builder().tenantId(tenantId).platformTenantId(platformId).kind(kind)
                .parentTenantId(parent).hotelCode(code).name("N" + tenantId)
                .status(PlatformTenantMapping.Status.ACTIVE).build();
    }

    private static Jwt jwt(UUID... tenants) {
        return new Jwt("t", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"),
                Map.of("sub", UUID.randomUUID().toString(),
                        "mikos_tenant_ids", java.util.Arrays.stream(tenants).map(UUID::toString).toList()));
    }
}
