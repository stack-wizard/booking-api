package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmScope;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Who is in which team today, and which owner / team the current user may hand a record to. */
@Service
public class CrmTeamDirectory {
    private final CrmTeamMemberRepository memberRepo;
    private final AppUserRepository appUserRepo;
    private final CrmAccessContext accessContext;

    public CrmTeamDirectory(CrmTeamMemberRepository memberRepo, AppUserRepository appUserRepo, CrmAccessContext accessContext) {
        this.memberRepo = memberRepo;
        this.appUserRepo = appUserRepo;
        this.accessContext = accessContext;
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
