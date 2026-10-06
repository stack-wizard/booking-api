package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
@RequestScope(proxyMode = ScopedProxyMode.TARGET_CLASS)
public class CrmAccessContext {

    private final AuthUserAccessor authUserAccessor;
    private final CrmTeamMemberRepository teamMemberRepository;
    private final CrmTeamRepository teamRepository;

    private Long currentUserId;
    private Set<CrmPermission> permissions;
    private CrmScope scope;
    private Set<Long> teamUserIds;
    private Set<Long> teamIds;
    private Set<Long> ledTeamIds;
    private Set<CrmRole> crmRoles;
    private boolean initialized;

    public CrmAccessContext(AuthUserAccessor authUserAccessor,
                            CrmTeamMemberRepository teamMemberRepository,
                            CrmTeamRepository teamRepository) {
        this.authUserAccessor = authUserAccessor;
        this.teamMemberRepository = teamMemberRepository;
        this.teamRepository = teamRepository;
    }

    public void require(CrmPermission permission) {
        ensureInitialized();
        if (!permissions.contains(permission)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Missing CRM permission: " + permission);
        }
    }

    public boolean has(CrmPermission permission) {
        ensureInitialized();
        return permissions.contains(permission);
    }

    public CrmScope scope() {
        ensureInitialized();
        return scope;
    }

    public Long currentUserId() {
        ensureInitialized();
        return currentUserId;
    }

    public Set<Long> teamUserIds() {
        ensureInitialized();
        return teamUserIds;
    }

    /** Teams whose records are visible (led teams with sub-teams, plus member teams for coordinators). */
    public Set<Long> teamIds() {
        ensureInitialized();
        return teamIds;
    }

    /** Teams the current user leads, including sub-teams. */
    public Set<Long> ledTeamIds() {
        ensureInitialized();
        return ledTeamIds;
    }

    public Set<CrmRole> crmRoles() {
        ensureInitialized();
        return crmRoles;
    }

    public List<String> roleNamesForMe() {
        ensureInitialized();
        Set<String> names = new HashSet<>();
        AppUser user = authUserAccessor.currentAppUser().orElse(null);
        if (user != null && user.getRole() != null) {
            names.add(user.getRole().name());
        }
        for (CrmRole role : crmRoles) {
            names.add(role.name());
        }
        return names.stream().sorted().toList();
    }

    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        AppUser user = authUserAccessor.requireAppUser();
        currentUserId = user.getId();
        crmRoles = PlatformRoleMapper.mapCrmRoles(mikosRolesFromJwt());
        permissions = CrmPermissionCatalog.permissionsFor(crmRoles, user.getRole());
        CrmScope base = CrmPermissionCatalog.scopeFor(crmRoles, user.getRole());
        if (base == CrmScope.ALL) {
            scope = CrmScope.ALL;
            teamUserIds = Set.of();
            teamIds = Set.of();
            ledTeamIds = Set.of();
        } else {
            Long tenantId = TenantResolver.requireTenantId();
            Map<Long, Long> parents = new HashMap<>();
            for (CrmTeam team : teamRepository.findByTenantIdOrderByNameAsc(tenantId)) {
                parents.put(team.getId(), team.getParentTeamId());
            }
            CrmTeamTree.Visibility visibility = CrmTeamTree.resolve(currentUserId, base == CrmScope.TEAM,
                    teamMemberRepository.findActive(tenantId, LocalDate.now()), parents);
            scope = visibility.empty() ? CrmScope.OWN : CrmScope.TEAM;
            Set<Long> users = new HashSet<>(visibility.userIds());
            users.add(currentUserId);
            teamUserIds = users;
            teamIds = visibility.teamIds();
            ledTeamIds = visibility.ledTeamIds();
        }
        initialized = true;
    }

    private static Collection<String> mikosRolesFromJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof Jwt jwt)) {
            return List.of();
        }
        Object raw = jwt.getClaim("mikos_roles");
        if (raw instanceof List<?> list) {
            return list.stream()
                    .map(o -> o == null ? null : o.toString())
                    .filter(s -> s != null && !s.isBlank())
                    .toList();
        }
        if (raw instanceof String s && !s.isBlank()) {
            return List.of(s.trim());
        }
        return List.of();
    }
}
