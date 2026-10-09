package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.TenantSetupStatusDto;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.model.TenantConfig;
import com.stackwizard.booking_api.repository.FiscalBusinessPremiseRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.TenantConfigRepository;
import com.stackwizard.booking_api.repository.TenantIntegrationConfigRepository;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformChild;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TenantSetupServiceTest {
    static final UUID ORG = UUID.fromString("11111111-1111-1111-1111-111111111111");
    static final UUID DH = UUID.fromString("21111111-1111-1111-1111-111111111111");
    static final UUID AA = UUID.fromString("31111111-1111-1111-1111-111111111111");

    PlatformTenantMappingRepository mappingRepo = mock(PlatformTenantMappingRepository.class);
    PlatformTenantResolver resolver = mock(PlatformTenantResolver.class);
    PlatformDirectoryClient directory = mock(PlatformDirectoryClient.class);
    TenantConfigRepository tenantConfigRepo = mock(TenantConfigRepository.class);
    ResourceRepository resourceRepo = mock(ResourceRepository.class);
    OperaHotelRepository operaRepo = mock(OperaHotelRepository.class);
    FiscalBusinessPremiseRepository fiscalRepo = mock(FiscalBusinessPremiseRepository.class);
    TenantIntegrationConfigRepository integrationRepo = mock(TenantIntegrationConfigRepository.class);
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    TenantSetupService service = new TenantSetupService(mappingRepo, resolver, directory, tenantConfigRepo,
            resourceRepo, operaRepo, fiscalRepo, integrationRepo, jdbc);

    /** All mappings by platform id, as the database would hold them. */
    List<PlatformTenantMapping> stored = new ArrayList<>();

    @BeforeEach
    void setUp() {
        AtomicLong ids = new AtomicLong(0);
        when(mappingRepo.nextTenantId()).thenAnswer(inv -> ids.incrementAndGet());
        when(mappingRepo.saveAndFlush(any(PlatformTenantMapping.class))).thenAnswer(inv -> {
            PlatformTenantMapping m = inv.getArgument(0);
            stored.add(m);
            return m;
        });
        when(mappingRepo.save(any(PlatformTenantMapping.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mappingRepo.findByPlatformTenantId(any(UUID.class))).thenAnswer(inv ->
                stored.stream().filter(m -> m.getPlatformTenantId().equals(inv.getArgument(0))).findFirst());
        when(mappingRepo.findByTenantId(anyLong())).thenAnswer(inv ->
                stored.stream().filter(m -> m.getTenantId().equals(inv.getArgument(0))).findFirst());
        when(mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(anyLong())).thenAnswer(inv ->
                stored.stream().filter(m -> inv.getArgument(0).equals(m.getParentTenantId())).toList());
        when(directory.findTenant(any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void organizationWithoutHotelCannotBeRegistered() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of());

        assertThatThrownBy(() -> service.registerOrSync(ORG, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least one");
        assertThat(stored).isEmpty();
    }

    @Test
    void inactiveHotelsAreIgnored() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", false)));

        assertThatThrownBy(() -> service.registerOrSync(ORG, "t")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void registersTheOrganizationAndStartsHotelsInSetup() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(
                new PlatformChild(DH, "Demo Hotel", "DH", true), new PlatformChild(AA, "Alpha", "AA", true)));

        TenantSetupStatusDto status = service.registerOrSync(ORG, "t");

        assertThat(stored).hasSize(3);
        PlatformTenantMapping org = stored.get(0);
        assertThat(org.getKind()).isEqualTo(PlatformTenantMapping.Kind.ORG);
        assertThat(org.getStatus()).isEqualTo(PlatformTenantMapping.Status.ACTIVE);
        assertThat(stored.subList(1, 3)).allSatisfy(h -> {
            assertThat(h.getKind()).isEqualTo(PlatformTenantMapping.Kind.PROPERTY);
            assertThat(h.getStatus()).isEqualTo(PlatformTenantMapping.Status.SETUP);
            assertThat(h.getParentTenantId()).isEqualTo(org.getTenantId());
        });
        verify(resolver, times(3)).ensureTenantDefaults(anyLong());
        assertThat(status.registered()).isTrue();
        assertThat(status.properties()).hasSize(2);
    }

    @Test
    void syncingAgainAddsOnlyTheNewHotel() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo Hotel", "DH", true)));
        service.registerOrSync(ORG, "t");
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(
                new PlatformChild(DH, "Demo Hotel renamed", "DH", true), new PlatformChild(AA, "Alpha", "AA", true)));

        service.registerOrSync(ORG, "t");

        assertThat(stored).hasSize(3);
        assertThat(stored.get(1).getName()).isEqualTo("Demo Hotel renamed");
    }

    @Test
    void hotelAlreadyUnderAnotherOrganizationIsRejected() {
        UUID otherOrg = UUID.fromString("41111111-1111-1111-1111-111111111111");
        when(directory.listChildren(otherOrg, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(otherOrg, "t");
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));

        assertThatThrownBy(() -> service.registerOrSync(ORG, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different organization");
    }

    @Test
    void aHotelCannotBeRegisteredAsOrganization() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(ORG, "t");
        when(directory.listChildren(DH, "t")).thenReturn(List.of(new PlatformChild(AA, "Alpha", "AA", true)));

        assertThatThrownBy(() -> service.registerOrSync(DH, "t"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is a hotel");
    }

    @Test
    void unregisteredOrganizationHasEmptyStatus() {
        TenantSetupStatusDto status = service.status(ORG);

        assertThat(status.registered()).isFalse();
        assertThat(status.properties()).isEmpty();
    }

    @Test
    void activationNeedsTheRequiredSteps() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(ORG, "t");
        Long hotel = stored.get(1).getTenantId();

        assertThatThrownBy(() -> service.activate(ORG, hotel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Missing")
                .hasMessageContaining("Tenant configuration");
        assertThat(stored.get(1).getStatus()).isEqualTo(PlatformTenantMapping.Status.SETUP);
    }

    @Test
    void activationMovesTheHotelToActive() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(ORG, "t");
        PlatformTenantMapping hotel = stored.get(1);
        when(tenantConfigRepo.findByTenantId(hotel.getTenantId())).thenReturn(Optional.of(new TenantConfig()));
        when(resourceRepo.existsByTenantId(hotel.getTenantId())).thenReturn(true);

        TenantSetupStatusDto.PropertyStatus result = service.activate(ORG, hotel.getTenantId());

        assertThat(hotel.getStatus()).isEqualTo(PlatformTenantMapping.Status.ACTIVE);
        assertThat(result.status()).isEqualTo("ACTIVE");
    }

    @Test
    void hotelOfAnotherOrganizationCannotBeActivatedOrPromoted() {
        UUID otherOrg = UUID.fromString("41111111-1111-1111-1111-111111111111");
        when(directory.listChildren(otherOrg, "t")).thenReturn(List.of(new PlatformChild(AA, "Alpha", "AA", true)));
        service.registerOrSync(otherOrg, "t");
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(ORG, "t");
        Long foreignHotel = stored.get(1).getTenantId();

        assertThatThrownBy(() -> service.activate(ORG, foreignHotel)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.promoteCatalogToOrg(ORG, foreignHotel)).isInstanceOf(IllegalArgumentException.class);
        verify(jdbc, never()).queryForList(any(String.class), any(Object[].class));
    }

    @Test
    void promoteConflictIsReportedAsStateConflict() {
        when(directory.listChildren(ORG, "t")).thenReturn(List.of(new PlatformChild(DH, "Demo", "DH", true)));
        service.registerOrSync(ORG, "t");
        Long hotel = stored.get(1).getTenantId();
        when(jdbc.queryForList(any(String.class), any(Object[].class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThatThrownBy(() -> service.promoteCatalogToOrg(ORG, hotel))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Nothing was moved");
    }
}
