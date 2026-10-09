package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.CrmTeamProperty;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamPropertyRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stackwizard.booking_api.model.AppUser;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Service
public class CrmTeamService {
    private final CrmTeamRepository teamRepo;
    private final CrmTeamMemberRepository memberRepo;
    private final AppUserRepository appUserRepo;
    private final CrmAccessContext accessContext;
    private final CrmTeamDirectory teamDirectory;
    private final CrmTeamPropertyRepository teamPropertyRepo;
    private final TenantHierarchy hierarchy;

    public CrmTeamService(CrmTeamRepository teamRepo,
                          CrmTeamMemberRepository memberRepo,
                          AppUserRepository appUserRepo,
                          CrmAccessContext accessContext,
                          CrmTeamDirectory teamDirectory,
                          CrmTeamPropertyRepository teamPropertyRepo,
                          TenantHierarchy hierarchy) {
        this.teamRepo = teamRepo;
        this.memberRepo = memberRepo;
        this.appUserRepo = appUserRepo;
        this.accessContext = accessContext;
        this.teamDirectory = teamDirectory;
        this.teamPropertyRepo = teamPropertyRepo;
        this.hierarchy = hierarchy;
    }

    /** What the caller sees: ALL / TEAM / OWN, the teams they lead and every team and user that is visible. */
    public record MyScope(Long userId, String scope, Set<Long> ledTeamIds, Set<Long> teamIds, Set<Long> userIds) {
    }

    public MyScope myScope() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return new MyScope(accessContext.currentUserId(), accessContext.scope().name(),
                accessContext.ledTeamIds(), accessContext.teamIds(), accessContext.teamUserIds());
    }

    /** Pickable users for owner / member fields, with the teams they are active in today. */
    public record CrmUser(Long id, String username, List<Long> activeTeamIds) {
    }

    public List<CrmUser> users() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        List<CrmTeamMember> active = memberRepo.findActive(tenantId, LocalDate.now());
        Map<Long, AppUser> byId = new LinkedHashMap<>();
        appUserRepo.findByTenantIdOrderByUsernameAsc(tenantId).stream()
                .filter(u -> u.getPlatformUserId() != null)
                .forEach(u -> byId.put(u.getId(), u));
        Set<Long> extra = new HashSet<>();
        active.forEach(m -> extra.add(m.getAppUserId()));
        extra.add(accessContext.currentUserId());
        extra.removeAll(byId.keySet());
        appUserRepo.findAllById(extra).forEach(u -> byId.put(u.getId(), u));
        return byId.values().stream()
                .sorted(Comparator.comparing(AppUser::getUsername, String.CASE_INSENSITIVE_ORDER))
                .map(u -> new CrmUser(u.getId(), u.getUsername(), active.stream()
                        .filter(m -> m.getAppUserId().equals(u.getId()))
                        .map(CrmTeamMember::getTeamId).distinct().toList()))
                .toList();
    }

    public List<CrmTeam> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return teamRepo.findByTenantIdOrderByNameAsc(TenantResolver.requireOrgTenantId());
    }

    public Optional<CrmTeam> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return teamRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId());
    }

    @Transactional
    public CrmTeam create(CrmTeam team) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        team.setId(null);
        team.setTenantId(TenantResolver.requireOrgTenantId());
        validateParent(team.getTenantId(), null, team.getParentTeamId());
        if (team.getActive() == null) {
            team.setActive(true);
        }
        return teamRepo.save(team);
    }

    @Transactional
    public CrmTeam update(Long id, CrmTeam changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmTeam existing = teamRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + id));
        existing.setName(changes.getName());
        validateParent(existing.getTenantId(), existing.getId(), changes.getParentTeamId());
        existing.setParentTeamId(changes.getParentTeamId());
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return teamRepo.save(existing);
    }

    /** Explicit hotels of every team of the chain; a team without rows covers every hotel. */
    public List<CrmTeamProperty> allProperties() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return teamPropertyRepo.findByTenantId(TenantResolver.requireOrgTenantId());
    }

    /**
     * Replaces the hotels of a team (empty = every hotel). A sub-team can only work for hotels its parent works
     * for, and a parent cannot drop a hotel one of its sub-teams still uses.
     */
    @Transactional
    public List<CrmTeamProperty> replaceProperties(Long teamId, List<Long> propertyTenantIds) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmTeam team = teamRepo.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        Set<Long> wanted = new java.util.LinkedHashSet<>(propertyTenantIds == null ? List.of() : propertyTenantIds);
        wanted.forEach(id -> hierarchy.requirePropertyOf(id, tenantId));
        if (!wanted.isEmpty() && team.getParentTeamId() != null) {
            var parent = teamDirectory.coverageOf(tenantId, team.getParentTeamId());
            if (!parent.allHotels() && !parent.propertyIds().containsAll(wanted)) {
                throw new IllegalArgumentException("A sub-team can only work for hotels its parent team works for");
            }
        }
        for (CrmTeam child : teamRepo.findByTenantIdOrderByNameAsc(tenantId)) {
            if (!teamId.equals(child.getParentTeamId())) {
                continue;
            }
            List<CrmTeamProperty> own = teamPropertyRepo.findByTenantIdAndTeamId(tenantId, child.getId());
            boolean outside = own.stream().anyMatch(r -> !wanted.isEmpty() && !wanted.contains(r.getPropertyTenantId()));
            if (outside) {
                throw new IllegalArgumentException("Sub-team " + child.getName() + " works for a hotel that is not in this list");
            }
        }
        teamPropertyRepo.deleteByTenantIdAndTeamId(tenantId, teamId);
        teamPropertyRepo.flush();
        List<CrmTeamProperty> rows = wanted.stream()
                .map(id -> CrmTeamProperty.builder().tenantId(tenantId).teamId(teamId).propertyTenantId(id).build())
                .toList();
        return teamPropertyRepo.saveAll(rows);
    }

    public List<CrmTeamMember> members(Long teamId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireOrgTenantId();
        teamRepo.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        return memberRepo.findByTenantIdAndTeamIdOrderByValidFromDescIdDesc(tenantId, teamId);
    }

    @Transactional
    public CrmTeamMember addMember(Long teamId, CrmTeamMember member) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireOrgTenantId();
        teamRepo.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        if (member.getAppUserId() == null) {
            throw new IllegalArgumentException("appUserId is required");
        }
        teamDirectory.requireUser(tenantId, member.getAppUserId());
        if (memberRepo.findByTenantIdAndTeamIdAndAppUserIdAndValidToIsNull(tenantId, teamId, member.getAppUserId()).isPresent()) {
            throw new IllegalStateException("User is already a member of this team");
        }
        member.setId(null);
        member.setTenantId(tenantId);
        member.setTeamId(teamId);
        if (member.getTeamLead() == null) {
            member.setTeamLead(false);
        }
        if (member.getValidFrom() == null) {
            member.setValidFrom(LocalDate.now());
        }
        validatePeriod(member.getValidFrom(), member.getValidTo());
        return memberRepo.save(member);
    }

    /** Changes the lead flag or the membership period; ending a membership keeps the history. */
    @Transactional
    public CrmTeamMember updateMember(Long memberId, CrmTeamMember changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmTeamMember member = memberRepo.findByIdAndTenantId(memberId, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Team member not found: " + memberId));
        if (changes.getTeamLead() != null) {
            member.setTeamLead(changes.getTeamLead());
        }
        if (changes.getValidFrom() != null) {
            member.setValidFrom(changes.getValidFrom());
        }
        member.setValidTo(changes.getValidTo());
        validatePeriod(member.getValidFrom(), member.getValidTo());
        if (member.getValidTo() == null) {
            memberRepo.findByTenantIdAndTeamIdAndAppUserIdAndValidToIsNull(member.getTenantId(), member.getTeamId(), member.getAppUserId())
                    .filter(other -> !other.getId().equals(member.getId()))
                    .ifPresent(other -> {
                        throw new IllegalStateException("User already has an open membership in this team");
                    });
        }
        return memberRepo.save(member);
    }

    /** Ends the membership today; a membership that never became effective is deleted. */
    @Transactional
    public void removeMember(Long memberId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmTeamMember member = memberRepo.findByIdAndTenantId(memberId, TenantResolver.requireOrgTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Team member not found: " + memberId));
        LocalDate today = LocalDate.now();
        if (!member.getValidFrom().isBefore(today)) {
            memberRepo.delete(member);
            return;
        }
        if (member.getValidTo() == null || member.getValidTo().isAfter(today)) {
            member.setValidTo(today);
            memberRepo.save(member);
        }
    }

    private static void validatePeriod(LocalDate from, LocalDate to) {
        if (to != null && to.isBefore(from)) {
            throw new IllegalArgumentException("validTo must not be before validFrom");
        }
    }

    private void validateParent(Long tenantId, Long teamId, Long parentTeamId) {
        Long cursor = parentTeamId;
        for (int depth = 0; cursor != null; depth++) {
            if (cursor.equals(teamId) || depth > 50) {
                throw new IllegalArgumentException("Parent team would create a cycle");
            }
            Long current = cursor;
            cursor = teamRepo.findByIdAndTenantId(current, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Parent team not found: " + current))
                    .getParentTeamId();
        }
    }
}
