package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
class PlatformUserSyncServiceTest {

    @Mock
    private AppUserRepository userRepo;

    private PlatformUserSyncService service;

    @BeforeEach
    void setUp() {
        service = new PlatformUserSyncService(userRepo);
    }

    @Test
    void createsNewUserWhenUnknown() {
        UUID platformUserId = UUID.randomUUID();
        when(userRepo.findByPlatformUserId(platformUserId)).thenReturn(Optional.empty());
        when(userRepo.findByTenantIdAndUsername(1L, "alice")).thenReturn(Optional.empty());
        when(userRepo.findByUsername("alice")).thenReturn(Optional.empty());
        when(userRepo.save(any())).thenAnswer(inv -> {
            AppUser u = inv.getArgument(0);
            u.setId(42L);
            return u;
        });

        AppUser created = service.syncUser(platformUserId, "alice", 1L, List.of("BOOKING_ADMIN"));

        assertThat(created.getId()).isEqualTo(42L);
        assertThat(created.getPlatformUserId()).isEqualTo(platformUserId);
        assertThat(created.getRole()).isEqualTo(AppUser.Role.ADMIN);
        assertThat(created.getTenantId()).isEqualTo(1L);
        assertThat(created.getPasswordHash()).isNull();
    }

    @Test
    void linksExistingTenantUsername() {
        UUID platformUserId = UUID.randomUUID();
        AppUser existing = AppUser.builder()
                .id(7L)
                .tenantId(1L)
                .username("alice")
                .role(AppUser.Role.CASHIER)
                .employeeNumber("E1")
                .build();
        when(userRepo.findByPlatformUserId(platformUserId)).thenReturn(Optional.empty());
        when(userRepo.findByTenantIdAndUsername(1L, "alice")).thenReturn(Optional.of(existing));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUser linked = service.syncUser(platformUserId, "alice", 1L, List.of("BOOKING_STAFF"));

        assertThat(linked.getPlatformUserId()).isEqualTo(platformUserId);
        assertThat(linked.getRole()).isEqualTo(AppUser.Role.STAFF);
        assertThat(linked.getEmployeeNumber()).isEqualTo("E1");
        assertThat(linked.getId()).isEqualTo(7L);
    }

    @Test
    void updatesRoleOnExistingPlatformLink() {
        UUID platformUserId = UUID.randomUUID();
        AppUser existing = AppUser.builder()
                .id(7L)
                .platformUserId(platformUserId)
                .tenantId(1L)
                .username("alice")
                .role(AppUser.Role.STAFF)
                .build();
        when(userRepo.findByPlatformUserId(platformUserId)).thenReturn(Optional.of(existing));
        when(userRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AppUser synced = service.syncUser(platformUserId, "alice", 1L, List.of("BOOKING_ADMIN"));

        assertThat(synced.getRole()).isEqualTo(AppUser.Role.ADMIN);
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(userRepo).save(captor.capture());
        assertThat(captor.getValue().getRole()).isEqualTo(AppUser.Role.ADMIN);
    }

    @Test
    void rejectsMissingBookingRole() {
        UUID platformUserId = UUID.randomUUID();
        assertThatThrownBy(() -> service.syncUser(platformUserId, "alice", 1L, List.of("VOID_OPERATOR")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("No booking role");
    }
}
