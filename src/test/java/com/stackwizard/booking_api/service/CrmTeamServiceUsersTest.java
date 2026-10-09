package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamPropertyRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CrmTeamServiceUsersTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void ownerListIncludesPlatformUsersOfTheHotels() {
        TenantContext.setScope(1L, null);
        AppUserRepository users = mock(AppUserRepository.class);
        CrmTeamMemberRepository members = mock(CrmTeamMemberRepository.class);
        CrmAccessContext access = mock(CrmAccessContext.class);
        when(access.currentUserId()).thenReturn(9L);
        when(members.findActive(any(), any())).thenReturn(List.of());
        when(users.findPlatformUsersOfChain(1L)).thenReturn(List.of(
                user(4L, "ana"),
                user(5L, "pero")));
        when(users.findAllById(any())).thenReturn(List.of());

        CrmTeamService service = new CrmTeamService(
                mock(CrmTeamRepository.class), members, users, access,
                mock(CrmTeamDirectory.class), mock(CrmTeamPropertyRepository.class), mock(TenantHierarchy.class));

        assertThat(service.users()).extracting(CrmTeamService.CrmUser::username).containsExactly("ana", "pero");
    }

    private static AppUser user(long id, String username) {
        return AppUser.builder().id(id).tenantId(2L).username(username).platformUserId(UUID.randomUUID())
                .role(AppUser.Role.STAFF).build();
    }
}
