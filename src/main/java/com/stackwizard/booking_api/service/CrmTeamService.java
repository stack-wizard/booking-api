package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmTeam;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class CrmTeamService {
    private final CrmTeamRepository teamRepo;
    private final CrmTeamMemberRepository memberRepo;
    private final AppUserRepository appUserRepo;
    private final CrmAccessContext accessContext;

    public CrmTeamService(CrmTeamRepository teamRepo,
                          CrmTeamMemberRepository memberRepo,
                          AppUserRepository appUserRepo,
                          CrmAccessContext accessContext) {
        this.teamRepo = teamRepo;
        this.memberRepo = memberRepo;
        this.appUserRepo = appUserRepo;
        this.accessContext = accessContext;
    }

    public List<CrmTeam> findAll() {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return teamRepo.findByTenantIdOrderByNameAsc(TenantResolver.requireTenantId());
    }

    public Optional<CrmTeam> findById(Long id) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        return teamRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmTeam create(CrmTeam team) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        team.setId(null);
        team.setTenantId(TenantResolver.requireTenantId());
        if (team.getActive() == null) {
            team.setActive(true);
        }
        return teamRepo.save(team);
    }

    @Transactional
    public CrmTeam update(Long id, CrmTeam changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmTeam existing = teamRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + id));
        existing.setName(changes.getName());
        existing.setParentTeamId(changes.getParentTeamId());
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        return teamRepo.save(existing);
    }

    public List<CrmTeamMember> members(Long teamId) {
        accessContext.require(CrmPermission.ACCOUNT_READ);
        Long tenantId = TenantResolver.requireTenantId();
        teamRepo.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        return memberRepo.findByTenantIdAndTeamId(tenantId, teamId);
    }

    @Transactional
    public CrmTeamMember addMember(Long teamId, CrmTeamMember member) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        teamRepo.findByIdAndTenantId(teamId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Team not found: " + teamId));
        if (member.getAppUserId() == null) {
            throw new IllegalArgumentException("appUserId is required");
        }
        appUserRepo.findByIdAndTenantId(member.getAppUserId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + member.getAppUserId()));
        member.setId(null);
        member.setTenantId(tenantId);
        member.setTeamId(teamId);
        if (member.getTeamLead() == null) {
            member.setTeamLead(false);
        }
        return memberRepo.save(member);
    }

    @Transactional
    public void removeMember(Long memberId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        CrmTeamMember member = memberRepo.findByIdAndTenantId(memberId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new IllegalArgumentException("Team member not found: " + memberId));
        memberRepo.delete(member);
    }
}
