package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmReassignment;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmReassignmentRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.CrmScope;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Moves the open work of one user (who left, changed team or is on long leave) to another rep or a team queue.
 * Closed records keep their original owner so history and reports stay correct.
 */
@Service
public class CrmReassignmentService {
    static final List<Event.Status> OPEN_EVENT_STATUSES = List.of(Event.Status.INQUIRY, Event.Status.TENTATIVE, Event.Status.DEFINITE);

    private final CrmLeadRepository leadRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final CrmAccountRepository accountRepo;
    private final EventRepository eventRepo;
    private final CrmTeamMemberRepository memberRepo;
    private final CrmTeamRepository teamRepo;
    private final CrmReassignmentRepository reassignmentRepo;
    private final CrmTeamDirectory teamDirectory;
    private final CrmAccessContext accessContext;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public CrmReassignmentService(CrmLeadRepository leadRepo,
                                  CrmOpportunityRepository opportunityRepo,
                                  CrmAccountRepository accountRepo,
                                  EventRepository eventRepo,
                                  CrmTeamMemberRepository memberRepo,
                                  CrmTeamRepository teamRepo,
                                  CrmReassignmentRepository reassignmentRepo,
                                  CrmTeamDirectory teamDirectory,
                                  CrmAccessContext accessContext) {
        this.leadRepo = leadRepo;
        this.opportunityRepo = opportunityRepo;
        this.accountRepo = accountRepo;
        this.eventRepo = eventRepo;
        this.memberRepo = memberRepo;
        this.teamRepo = teamRepo;
        this.reassignmentRepo = reassignmentRepo;
        this.teamDirectory = teamDirectory;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public CrmTeamDtos.ReassignCounts preview(Long fromUserId) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        requireManages(fromUserId);
        return new CrmTeamDtos.ReassignCounts(
                openLeads(tenantId, fromUserId).size(),
                openOpportunities(tenantId, fromUserId).size(),
                activeAccounts(tenantId, fromUserId).size(),
                openEvents(tenantId, fromUserId).size());
    }

    public List<CrmReassignment> history() {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        return reassignmentRepo.findTop50ByTenantIdOrderByCreatedAtDesc(TenantResolver.requireTenantId());
    }

    @Transactional
    public CrmReassignment reassign(CrmTeamDtos.ReassignRequest request) {
        accessContext.require(CrmPermission.OPPORTUNITY_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        if (request == null || request.fromUserId() == null) {
            throw new IllegalArgumentException("fromUserId is required");
        }
        Long from = request.fromUserId();
        Long to = request.toUserId();
        boolean leads = !Boolean.FALSE.equals(request.includeLeads());
        boolean opportunities = !Boolean.FALSE.equals(request.includeOpportunities());
        boolean accounts = !Boolean.FALSE.equals(request.includeAccounts());
        boolean events = !Boolean.FALSE.equals(request.includeEvents());
        if (to == null && request.toTeamId() == null) {
            throw new IllegalArgumentException("toUserId or toTeamId is required");
        }
        if (to == null && (opportunities || accounts || events)) {
            throw new IllegalArgumentException("Only leads can go to a team queue; pick a rep for opportunities, accounts and events");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("fromUserId and toUserId must differ");
        }
        if (to != null) {
            teamDirectory.requireUser(tenantId, to);
        }
        if (request.toTeamId() != null) {
            teamRepo.findByIdAndTenantId(request.toTeamId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Team not found: " + request.toTeamId()));
        }
        requireManages(from);
        teamDirectory.requireAssignable(tenantId, to, request.toTeamId());
        Long team = teamDirectory.resolveTeam(tenantId, to, request.toTeamId(), null);

        int leadCount = 0;
        int opportunityCount = 0;
        int accountCount = 0;
        int eventCount = 0;
        if (leads) {
            for (CrmLead lead : openLeads(tenantId, from)) {
                lead.setOwnerUserId(to);
                lead.setTeamId(team != null ? team : lead.getTeamId());
                lead.setAssignedAt(OffsetDateTime.now());
                leadRepo.save(lead);
                leadCount++;
            }
        }
        if (opportunities) {
            for (CrmOpportunity opportunity : openOpportunities(tenantId, from)) {
                opportunity.setOwnerUserId(to);
                opportunity.setTeamId(team != null ? team : opportunity.getTeamId());
                opportunityRepo.save(opportunity);
                opportunityCount++;
            }
        }
        if (accounts) {
            for (CrmAccount account : activeAccounts(tenantId, from)) {
                account.setOwnerUserId(to);
                account.setTeamId(team != null ? team : account.getTeamId());
                accountRepo.save(account);
                accountCount++;
            }
        }
        if (events) {
            for (Event event : openEvents(tenantId, from)) {
                event.setOwnerUserId(to);
                event.setTeamId(team != null ? team : event.getTeamId());
                eventRepo.save(event);
                eventCount++;
            }
        }
        int endedMemberships = 0;
        if (Boolean.TRUE.equals(request.endMemberships())) {
            accessContext.require(CrmPermission.PIPELINE_CONFIG);
            endedMemberships = endMemberships(tenantId, from);
        }

        var counts = objectMapper.createObjectNode();
        counts.put("leads", leadCount);
        counts.put("opportunities", opportunityCount);
        counts.put("accounts", accountCount);
        counts.put("events", eventCount);
        counts.put("endedMemberships", endedMemberships);
        return reassignmentRepo.save(CrmReassignment.builder()
                .tenantId(tenantId)
                .fromUserId(from)
                .toUserId(to)
                .toTeamId(team)
                .counts(counts)
                .note(request.note())
                .createdBy(accessContext.currentUserId())
                .build());
    }

    private int endMemberships(Long tenantId, Long userId) {
        LocalDate today = LocalDate.now();
        int ended = 0;
        for (CrmTeamMember member : memberRepo.findByTenantIdAndAppUserIdAndValidToIsNull(tenantId, userId)) {
            if (!member.getValidFrom().isBefore(today)) {
                memberRepo.delete(member);
            } else {
                member.setValidTo(today);
                memberRepo.save(member);
            }
            ended++;
        }
        return ended;
    }

    /** ALL scope may move anyone's work; a team lead only the work of people in teams they lead. */
    private void requireManages(Long userId) {
        if (accessContext.scope() == CrmScope.ALL) {
            return;
        }
        if (userId.equals(accessContext.currentUserId()) || !accessContext.teamUserIds().contains(userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only a team lead of this user can reassign their work");
        }
    }

    private List<CrmLead> openLeads(Long tenantId, Long userId) {
        return leadRepo.findByTenantIdAndOwnerUserIdAndStatusIn(tenantId, userId, CrmLeadAssignmentService.OPEN_STATUSES);
    }

    private List<CrmOpportunity> openOpportunities(Long tenantId, Long userId) {
        return opportunityRepo.findByTenantIdAndOwnerUserIdAndStatus(tenantId, userId, CrmOpportunity.Status.OPEN);
    }

    private List<CrmAccount> activeAccounts(Long tenantId, Long userId) {
        return accountRepo.findByTenantIdAndOwnerUserIdAndActiveTrue(tenantId, userId);
    }

    private List<Event> openEvents(Long tenantId, Long userId) {
        return eventRepo.findByTenantIdAndOwnerUserIdAndStatusInAndDateToGreaterThanEqual(tenantId, userId, OPEN_EVENT_STATUSES, LocalDate.now());
    }
}
