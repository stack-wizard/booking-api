package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.PlatformUserDtos.PlatformUserDto;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.PlatformTenantMapping;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.CrmTeamCoverage;
import com.stackwizard.booking_api.security.PlatformAuthFilter;
import com.stackwizard.booking_api.security.PlatformRoleMapper;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformRole;
import com.stackwizard.booking_api.service.PlatformDirectoryClient.PlatformUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Lets team admins pick team members from the Mikos Platform users of the chain and its hotels. The person gets a
 * booking user on the spot, so they do not have to log in to booking first. Uses the caller's own Platform token.
 */
@Service
public class CrmPlatformUserService {
    private final PlatformDirectoryClient directory;
    private final PlatformTenantMappingRepository mappingRepo;
    private final CrmTeamDirectory teamDirectory;
    private final CrmTeamRepository teamRepo;
    private final CrmTeamService teamService;
    private final PlatformUserSyncService userSync;
    private final AppUserRepository appUserRepo;
    private final CrmAccessContext accessContext;

    public CrmPlatformUserService(PlatformDirectoryClient directory,
                                  PlatformTenantMappingRepository mappingRepo,
                                  CrmTeamDirectory teamDirectory,
                                  CrmTeamRepository teamRepo,
                                  CrmTeamService teamService,
                                  PlatformUserSyncService userSync,
                                  AppUserRepository appUserRepo,
                                  CrmAccessContext accessContext) {
        this.directory = directory;
        this.mappingRepo = mappingRepo;
        this.teamDirectory = teamDirectory;
        this.teamRepo = teamRepo;
        this.teamService = teamService;
        this.userSync = userSync;
        this.appUserRepo = appUserRepo;
        this.accessContext = accessContext;
    }

    /** Platform users of the chain plus the hotels the team works for (every hotel when it has no restriction). */
    public List<PlatformUserDto> list(Long teamId, String bearerToken) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        return merged(requireTeamOrgId(teamId), teamId, bearerToken).values().stream()
                .map(Merged::toDto)
                .sorted(Comparator.comparing(PlatformUserDto::username, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    @Transactional
    public CrmTeamMember addMember(Long teamId, UUID platformUserId, Boolean teamLead, LocalDate validFrom,
                                   LocalDate validTo, String bearerToken) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long orgId = requireTeamOrgId(teamId);
        if (platformUserId == null) {
            throw new IllegalArgumentException("platformUserId is required");
        }
        // Never trust the client: the person must really be a Platform user of this chain.
        Merged found = merged(orgId, teamId, bearerToken).get(platformUserId);
        if (found == null) {
            throw new IllegalArgumentException("User is not a Platform user of this chain or the team's hotels");
        }
        if (!found.ready()) {
            throw new IllegalArgumentException("User needs the BOOKING application and a booking role "
                    + "(BOOKING_STAFF or BOOKING_ADMIN) in Mikos Platform");
        }
        AppUser appUser = userSync.syncUser(platformUserId, found.user.username(), orgId, found.roleNames());
        CrmTeamMember member = CrmTeamMember.builder()
                .appUserId(appUser.getId())
                .teamLead(teamLead)
                .validFrom(validFrom)
                .validTo(validTo)
                .build();
        return teamService.addMember(teamId, member);
    }

    private Long requireTeamOrgId(Long teamId) {
        Long orgId = TenantResolver.requireOrgTenantId();
        teamRepo.findByIdAndTenantId(teamId, orgId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        return orgId;
    }

    private Map<UUID, Merged> merged(Long orgId, Long teamId, String bearerToken) {
        PlatformTenantMapping org = mappingRepo.findByTenantId(orgId)
                .orElseThrow(() -> new IllegalStateException("Organization is not registered"));
        List<PlatformTenantMapping> hotels = mappingRepo.findByParentTenantIdOrderByHotelCodeAscTenantIdAsc(orgId);
        CrmTeamCoverage.Coverage coverage = teamDirectory.coverageOf(orgId, teamId);
        List<PlatformTenantMapping> wanted = hotels.stream()
                .filter(h -> coverage.allHotels() || coverage.propertyIds().contains(h.getTenantId()))
                .toList();

        Map<UUID, Merged> byUser = new LinkedHashMap<>();
        ResponseStatusException firstFailure = null;
        int succeeded = 0;
        List<PlatformTenantMapping> sources = new ArrayList<>();
        sources.add(org);
        sources.addAll(wanted);
        for (PlatformTenantMapping source : sources) {
            List<PlatformUser> users;
            try {
                users = directory.listUsers(source.getPlatformTenantId(), bearerToken);
            } catch (ResponseStatusException ex) {
                // A hotel the caller does not administer must not hide the rest.
                if (firstFailure == null) {
                    firstFailure = ex;
                }
                continue;
            }
            succeeded++;
            for (PlatformUser user : users) {
                if (user.userId() == null || !user.enabled()) {
                    continue;
                }
                Merged merged = byUser.computeIfAbsent(user.userId(), id -> new Merged(user));
                merged.add(source, org);
                merged.mergeRoles(user);
            }
        }
        if (succeeded == 0 && firstFailure != null) {
            throw firstFailure;
        }
        Set<UUID> ids = byUser.keySet();
        for (UUID id : ids) {
            appUserRepo.findByPlatformUserId(id).ifPresent(u -> byUser.get(id).appUserId = u.getId());
        }
        return byUser;
    }

    private static final class Merged {
        private final PlatformUser user;
        private final Set<String> roles = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private final Set<String> apps = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        private final List<Long> propertyTenantIds = new ArrayList<>();
        private boolean orgMember;
        private Long appUserId;

        private Merged(PlatformUser user) {
            this.user = user;
        }

        private void add(PlatformTenantMapping source, PlatformTenantMapping org) {
            if (source.getTenantId().equals(org.getTenantId())) {
                orgMember = true;
            } else if (!propertyTenantIds.contains(source.getTenantId())) {
                propertyTenantIds.add(source.getTenantId());
            }
        }

        private void mergeRoles(PlatformUser source) {
            if (source.roles() != null) {
                for (PlatformRole role : source.roles()) {
                    if (role != null && role.name() != null) {
                        roles.add(role.name());
                    }
                }
            }
            if (source.applicationCodes() != null) {
                apps.addAll(source.applicationCodes());
            }
        }

        private List<String> roleNames() {
            return List.copyOf(roles);
        }

        private boolean ready() {
            return apps.stream().anyMatch(a -> PlatformAuthFilter.APP_CODE.equalsIgnoreCase(a))
                    && PlatformRoleMapper.mapHighest(roles) != null;
        }

        private PlatformUserDto toDto() {
            return new PlatformUserDto(user.userId(), user.username(), roleNames(), ready(), orgMember,
                    List.copyOf(propertyTenantIds), appUserId);
        }
    }
}
