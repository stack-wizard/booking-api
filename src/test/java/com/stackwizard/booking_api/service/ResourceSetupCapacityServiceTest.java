package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.ResourceSetupCapacityRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ResourceSetupCapacityServiceTest {

    @Mock ResourceSetupCapacityRepository capacityRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock ResourceCompositionRepository compositionRepo;
    @Mock AllocationRepository allocationRepo;
    @Mock CrmAccessContext accessContext;

    ResourceSetupCapacityService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new ResourceSetupCapacityService(capacityRepo, resourceRepo, compositionRepo, allocationRepo, accessContext);
        when(capacityRepo.saveAll(anyList())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private void resource(Long id, String type) {
        when(resourceRepo.findByIdWithType(id)).thenReturn(Optional.of(Resource.builder().id(id).tenantId(1L)
                .resourceType(ResourceType.builder().code(type).name(type).build()).build()));
    }

    private ResourceSetupCapacity setup(ResourceSetupCapacity.SetupStyle style, int capacity) {
        return ResourceSetupCapacity.builder().setupStyle(style).capacity(capacity).build();
    }

    @Test
    void sunbedIsNotASpace() {
        resource(9L, "SUNBED");

        assertThatThrownBy(() -> service.replace(9L, List.of(setup(ResourceSetupCapacity.SetupStyle.THEATRE, 10))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SUNBED");
    }

    @Test
    void duplicateStyleIsRejected() {
        resource(3L, "MEETING_ROOM");

        assertThatThrownBy(() -> service.replace(3L, List.of(
                setup(ResourceSetupCapacity.SetupStyle.THEATRE, 200),
                setup(ResourceSetupCapacity.SetupStyle.THEATRE, 150))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate");
    }

    @Test
    void replaceStoresTenantScopedRows() {
        resource(3L, "COMPOSITION");

        List<ResourceSetupCapacity> saved = service.replace(3L, List.of(
                setup(ResourceSetupCapacity.SetupStyle.THEATRE, 200),
                setup(ResourceSetupCapacity.SetupStyle.CLASSROOM, 80)));

        verify(capacityRepo).deleteByTenantIdAndResourceId(1L, 3L);
        assertThat(saved).allSatisfy(s -> {
            assertThat(s.getTenantId()).isEqualTo(1L);
            assertThat(s.getResourceId()).isEqualTo(3L);
        });
    }
}
