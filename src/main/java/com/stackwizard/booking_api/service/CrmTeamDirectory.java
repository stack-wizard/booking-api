package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.CrmTeamProperty;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamPropertyRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmScope;
import com.stackwizard.booking_api.security.CrmTeamCoverage;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;

/** Who is in which team today, and which owner / team the current user may hand a record to. */
@Service
public class CrmTeamDirectory {
    private final CrmTeamMemberRepository memberRepo;
    private final AppUserRepository appUserRepo;
    private final CrmAccessContext accessContext;
    private final CrmTeamRepository teamRepo;
    private final CrmTeamPropertyRepository teamPropertyRepo;

    public CrmTeamDirectory(CrmTeamMemberRepository memberRepo, AppUserRepository appUserRepo, CrmAccessContext accessContext,
                            CrmTeamRepository teamRepo, CrmTeamPropertyRepository teamPropertyRepo) {
        this.memberRepo = memberRepo;
        this.appUserRepo = appUserRepo;
        this.accessContext = accessContext;
        this.teamRepo = teamRepo;
        this.teamPropertyRepo = teamPropertyRepo;
    }

    /** Hotels the team works for (own hotels, else the parent's, else the whole chain). */
    public CrmTeamCoverage.Coverage coverageOf(Long tenantId, Long teamId) {
        Map<Long, Long> parents = new HashMap<>();
        for (CrmTeam team : teamRepo.findByTenantIdOrderByNameAsc(tenantId)) {
            parents.put(team.getId(), team.getParentTeamId());
        }
        Map<Long, Set<Long>> hotels = new HashMap<>();
        for (CrmTeamProperty row : teamPropertyRepo.findByTenantId(tenantId)) {
            hotels.computeIfAbsent(row.getTeamId(), k -> new HashSet<>()).add(row.getPropertyTenantId());
        }
        return CrmTeamCoverage.effective(teamId, parents, hotels);
    }

    /**
     * Hotel of a new or changed record handed to a team. A team that works for one hotel only defaults a record
     * without hotel to it; a team with several hotels asks for a choice; a record's hotel must be one the team covers.
     */
    public Long resolveProperty(Long tenantId, Long teamId, Long propertyTenantId) {
        if (teamId == null) {
            return propertyTenantId;
        }
        CrmTeamCoverage.Coverage coverage = coverageOf(tenantId, teamId);
        if (coverage.allHotels()) {
            return propertyTenantId;
        }
        if (propertyTenantId == null) {
            if (coverage.propertyIds().size() == 1) {
                return coverage.propertyIds().iterator().next();
            }
            throw new IllegalArgumentException("The team works for several hotels; choose the hotel of the record");
        }
        requireCovers(tenantId, teamId, propertyTenantId);
        return propertyTenantId;
    }

    /** The team must work for the record's hotel; a record without hotel needs a team that covers every hotel. */
    public void requireCovers(Long tenantId, Long teamId, Long propertyTenantId) {
        if (teamId == null) {
            return;
        }
        CrmTeamCoverage.Coverage coverage = coverageOf(tenantId, teamId);
        if (coverage.allHotels() || (propertyTenantId != null && coverage.propertyIds().contains(propertyTenantId))) {
            return;
        }
        throw new IllegalArgumentException(propertyTenantId == null
                ? "The team works only for some hotels; a record for the whole chain needs a team that covers every hotel"
                : "The team does not work for the hotel of this record");
    }

    /**
     * A user that may own CRM records in the tenant: a tenant user, the caller (platform admins have no tenant)
     * or someone already in one of the tenant's teams.
     */
    public AppUser requireUser(Long tenantId, Long userId) {
        Optional<AppUser> user = appUserRepo.findByIdAndTenantId(userId, tenantId);
        if (user.isPresent()) {
            return user.get();
        }
        boolean known = userId.equals(accessContext.currentUserId())
                || memberRepo.findByTenantIdAndAppUserIdAndValidToIsNull(tenantId, userId).size() > 0;
        return appUserRepo.findById(userId).filter(u -> known)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));
    }

    public List<CrmTeamMember> active(Long tenantId) {
        return memberRepo.findActive(tenantId, LocalDate.now());
    }

    /** Earliest active membership; a record without an explicit team goes there. */
    public Optional<Long> primaryTeamOf(Long tenantId, Long userId) {
        if (userId == null) {
            return Optional.empty();
        }
        return active(tenantId).stream()
                .filter(m -> m.getAppUserId().equals(userId))
                .map(CrmTeamMember::getTeamId)
                .findFirst();
    }

    /** Active members of the team ordered by user id; leads only when the team has nobody else. */
    public List<Long> assignableMembers(Long tenantId, Long teamId) {
        List<CrmTeamMember> members = active(tenantId).stream()
                .filter(m -> m.getTeamId().equals(teamId))
                .toList();
        List<Long> reps = members.stream()
                .filter(m -> !Boolean.TRUE.equals(m.getTeamLead()))
                .map(CrmTeamMember::getAppUserId)
                .distinct().sorted().toList();
        if (!reps.isEmpty()) {
            return reps;
        }
        return members.stream().map(CrmTeamMember::getAppUserId).distinct().sorted().toList();
    }

    public boolean isActiveMember(Long tenantId, Long teamId, Long userId) {
        return active(tenantId).stream()
                .anyMatch(m -> m.getTeamId().equals(teamId) && m.getAppUserId().equals(userId));
    }

    /** Explicit team, else the owner's primary team, else the fallback. */
    public Long resolveTeam(Long tenantId, Long ownerUserId, Long explicitTeamId, Long fallbackTeamId) {
        if (explicitTeamId != null) {
            return explicitTeamId;
        }
        return primaryTeamOf(tenantId, ownerUserId).orElse(fallbackTeamId);
    }

    /**
     * Reps may only keep records for themselves; team leads may hand them to people and teams they lead;
     * ALL scope may assign anywhere.
     */
    public void requireAssignable(Long tenantId, Long ownerUserId, Long teamId) {
        if (accessContext.scope() == CrmScope.ALL) {
            return;
        }
        Long me = accessContext.currentUserId();
        boolean ownerOk = ownerUserId == null
                ? accessContext.scope() == CrmScope.TEAM
                : Objects.equals(ownerUserId, me) || accessContext.teamUserIds().contains(ownerUserId);
        if (!ownerOk) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only assign records to members of teams you lead");
        }
        if (teamId != null && !accessContext.teamIds().contains(teamId) && !isActiveMember(tenantId, teamId, me)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only assign records to your own or led teams");
        }
    }
}
