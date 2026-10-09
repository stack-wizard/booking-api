package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.PlatformUserDtos.PlatformUserDto;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmTeamCoverage;
import com.stackwizard.booking_api.security.TenantContext;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformRole;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CrmPlatformUserServiceTest {
    private static final UUID ORG = UUID.randomUUID();
    private static final UUID HOTEL_A = UUID.randomUUID();
    private static final UUID HOTEL_B = UUID.randomUUID();
    private static final UUID ANA = UUID.randomUUID();
    private static final UUID PERO = UUID.randomUUID();

    private PlatformDirectoryClient directory;
    private CrmTeamDirectory teamDirectory;
    private CrmTeamService teamService;
    private PlatformUserSyncService userSync;
    private AppUserRepository appUserRepo;
    private CrmPlatformUserService service;

    private static PlatformTenantMapping mapping(long id, UUID platformId, PlatformTenantMapping.Kind kind, Long parent) {
        return PlatformTenantMapping.builder().tenantId(id).platformTenantId(platformId).kind(kind).parentTenantId(parent).build();
    }

    private static PlatformUser user(UUID id, String name, String... roles) {
        return new PlatformUser(id, name, true, java.util.Arrays.stream(roles).map(r -> new PlatformRole(UUID.randomUUID(), r)).toList(),
                List.of("BOOKING"));
    }

    @BeforeEach
    void setUp() {
        TenantContext.setScope(1L, null);
        directory = mock(PlatformDirectoryClient.class);
        PlatformTenantMappingRepository mappings = mock(PlatformTenantMappingRepository.class);
        teamDirectory = mock(CrmTeamDirectory.class);
        CrmTeamRepository teams = mock(CrmTeamRepository.class);
        teamService = mock(CrmTeamService.class);
        userSync = mock(PlatformUserSyncService.class);
        appUserRepo = mock(AppUserRepository.class);
        when(mappings.findByTenantId(1L)).thenReturn(Optional.of(mapping(1L, ORG, PlatformTenantMapping.Kind.ORG, null)));
        when(mappings.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(1L)).thenReturn(List.of(
                mapping(2L, HOTEL_A, PlatformTenantMapping.Kind.PROPERTY, 1L),
                mapping(3L, HOTEL_B, PlatformTenantMapping.Kind.PROPERTY, 1L)));
        when(teams.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(CrmTeam.builder().id(7L).tenantId(1L).build()));
        when(directory.listUsers(ORG, "tok")).thenReturn(List.of(user(ANA, "ana", "BOOKING_STAFF", "BOOKING_SALES_REP")));
        when(directory.listUsers(HOTEL_A, "tok")).thenReturn(List.of(user(PERO, "pero", "BOOKING_SALES_REP")));
        when(directory.listUsers(HOTEL_B, "tok")).thenReturn(List.of(user(UUID.randomUUID(), "iva", "BOOKING_STAFF")));
        service = new CrmPlatformUserService(directory, mappings, teamDirectory, teams, teamService, userSync, appUserRepo,
                mock(CrmAccessContext.class));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void listsChainUsersAndOnlyHotelsTheTeamWorksFor() {
        when(teamDirectory.coverageOf(1L, 7L)).thenReturn(new CrmTeamCoverage.Coverage(false, Set.of(2L)));

        List<PlatformUserDto> users = service.list(7L, "tok");

        assertThat(users).extracting(PlatformUserDto::username).containsExactly("ana", "pero");
        PlatformUserDto ana = users.get(0);
        assertThat(ana.orgMember()).isTrue();
        assertThat(ana.ready()).isTrue();
        PlatformUserDto pero = users.get(1);
        assertThat(pero.propertyTenantIds()).containsExactly(2L);
        assertThat(pero.ready()).isFalse(); // only a CRM role, no BOOKING_STAFF/ADMIN
    }

    @Test
    void addingAPlatformUserCreatesTheBookingUserAndTheMembership() {
        when(teamDirectory.coverageOf(1L, 7L)).thenReturn(CrmTeamCoverage.Coverage.ALL);
        when(userSync.syncUser(any(), any(), any(), any())).thenReturn(AppUser.builder().id(55L).build());
        when(teamService.addMember(any(), any())).thenAnswer(i -> i.getArgument(1));

        CrmTeamMember saved = service.addMember(7L, ANA, true, null, null, "tok");

        assertThat(saved.getAppUserId()).isEqualTo(55L);
        assertThat(saved.getTeamLead()).isTrue();
        verify(userSync).syncUser(ANA, "ana", 1L, List.of("BOOKING_SALES_REP", "BOOKING_STAFF"));
    }

    @Test
    void refusesUsersWithoutBookingRoleAndStrangers() {
        when(teamDirectory.coverageOf(1L, 7L)).thenReturn(CrmTeamCoverage.Coverage.ALL);

        assertThatThrownBy(() -> service.addMember(7L, PERO, false, null, null, "tok"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("booking role");
        assertThatThrownBy(() -> service.addMember(7L, UUID.randomUUID(), false, null, null, "tok"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not a Platform user");
        verify(userSync, never()).syncUser(any(), any(), any(), any());
    }

    @Test
    void aHotelTheCallerCannotAdministerDoesNotHideTheRest() {
        when(teamDirectory.coverageOf(1L, 7L)).thenReturn(CrmTeamCoverage.Coverage.ALL);
        when(directory.listUsers(HOTEL_B, "tok")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no"));

        assertThat(service.list(7L, "tok")).extracting(PlatformUserDto::username).containsExactly("ana", "pero");
    }

    @Test
    void failsWhenNothingCouldBeRead() {
        when(teamDirectory.coverageOf(1L, 7L)).thenReturn(CrmTeamCoverage.Coverage.ALL);
        when(directory.listUsers(any(), any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "no"));

        assertThatThrownBy(() -> service.list(7L, "tok")).isInstanceOf(ResponseStatusException.class);
    }
}
