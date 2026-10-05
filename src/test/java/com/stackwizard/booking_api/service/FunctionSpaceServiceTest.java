package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.FunctionSpace;
import com.stackwizard.booking_api.model.FunctionSpaceSetup;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.FunctionSpaceRepository;
import com.stackwizard.booking_api.repository.FunctionSpaceSetupRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.security.TenantResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FunctionSpaceServiceTest {

    @Mock FunctionSpaceRepository spaceRepo;
    @Mock FunctionSpaceSetupRepository setupRepo;
    @Mock ResourceRepository resourceRepo;
    @Mock CrmAccessContext accessContext;

    FunctionSpaceService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new FunctionSpaceService(spaceRepo, setupRepo, resourceRepo, accessContext);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void createRejectsNonMeetingResourceType() {
        Resource resource = Resource.builder()
                .id(9L)
                .tenantId(1L)
                .resourceType(com.stackwizard.booking_api.model.ResourceType.builder()
                        .id(1L).code("SUNBED").name("Sunbed").build())
                .build();
        when(resourceRepo.findByIdWithType(9L)).thenReturn(Optional.of(resource));

        FunctionSpace space = FunctionSpace.builder().resourceId(9L).build();

        assertThatThrownBy(() -> service.create(space))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MEETING_ROOM");
    }

    @Test
    void createSetupRequiresPositiveCapacity() {
        when(spaceRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(
                FunctionSpace.builder().id(3L).tenantId(1L).resourceId(9L).active(true).build()));

        FunctionSpaceSetup setup = FunctionSpaceSetup.builder()
                .setupStyle(FunctionSpace.SetupStyle.THEATRE)
                .capacity(0)
                .build();

        assertThatThrownBy(() -> service.createSetup(3L, setup))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }

    @Test
    void createPersistsMeetingRoomSpace() {
        Resource resource = Resource.builder()
                .id(9L)
                .tenantId(1L)
                .resourceType(com.stackwizard.booking_api.model.ResourceType.builder()
                        .id(2L).code("MEETING_ROOM").name("Meeting Room").build())
                .build();
        when(resourceRepo.findByIdWithType(9L)).thenReturn(Optional.of(resource));
        when(spaceRepo.findByTenantIdAndResourceId(1L, 9L)).thenReturn(Optional.empty());
        when(spaceRepo.save(any())).thenAnswer(inv -> {
            FunctionSpace s = inv.getArgument(0);
            s.setId(11L);
            return s;
        });

        FunctionSpace saved = service.create(FunctionSpace.builder().resourceId(9L).build());

        assertThat(saved.getId()).isEqualTo(11L);
        assertThat(saved.getTenantId()).isEqualTo(1L);
        assertThat(saved.getMinDurationMinutes()).isEqualTo(60);
        verify(spaceRepo).save(any());
    }
}
