package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAssignmentRule;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmAssignmentRuleRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Picks owner and team for a new lead: an existing account keeps its owner, otherwise the first matching
 * assignment rule (by priority) decides the team and its strategy picks the rep, otherwise the segment's
 * default team, otherwise whoever created the lead.
 */
@Service
public class CrmLeadAssignmentService {
    static final List<CrmLead.Status> OPEN_STATUSES = List.of(CrmLead.Status.NEW, CrmLead.Status.WORKING, CrmLead.Status.QUALIFIED);

    public record Decision(Long ownerUserId, Long teamId, Long ruleId, String reason) {
    }

    private final CrmAssignmentRuleRepository ruleRepo;
    private final CrmLeadRepository leadRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmTeamDirectory teamDirectory;
    private final CrmSegmentService segmentService;

    public CrmLeadAssignmentService(CrmAssignmentRuleRepository ruleRepo,
                                    CrmLeadRepository leadRepo,
                                    CrmAccountRepository accountRepo,
                                    CrmContactRepository contactRepo,
                                    CrmTeamDirectory teamDirectory,
                                    CrmSegmentService segmentService) {
        this.ruleRepo = ruleRepo;
        this.leadRepo = leadRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.teamDirectory = teamDirectory;
        this.segmentService = segmentService;
    }

    /** Applies the decision to the lead (not saved). {@code fallbackUserId} owns the lead when nothing matches. */
    @Transactional
    public Decision assign(CrmLead lead, Long fallbackUserId) {
        Decision decision = decide(lead, fallbackUserId);
        lead.setOwnerUserId(decision.ownerUserId());
        lead.setTeamId(decision.teamId());
        lead.setAssignmentRuleId(decision.ruleId());
        lead.setAssignedAt(OffsetDateTime.now());
        return decision;
    }

    Decision decide(CrmLead lead, Long fallbackUserId) {
        Long tenantId = lead.getTenantId();
        Optional<CrmAccount> account = matchAccount(tenantId, lead);
        if (account.isPresent()) {
            CrmAccount a = account.get();
            if (!StringUtils.hasText(lead.getSegment()) && StringUtils.hasText(a.getSegment())) {
                lead.setSegment(a.getSegment());
            }
            boolean ownerActive = a.getOwnerUserId() != null && teamDirectory.primaryTeamOf(tenantId, a.getOwnerUserId()).isPresent();
            if (ownerActive || (a.getOwnerUserId() != null && a.getTeamId() == null)) {
                Long team = a.getTeamId() != null ? a.getTeamId() : teamDirectory.primaryTeamOf(tenantId, a.getOwnerUserId()).orElse(null);
                return new Decision(a.getOwnerUserId(), team, null, "account " + a.getName());
            }
            if (a.getTeamId() != null) {
                return new Decision(leastLoaded(tenantId, a.getTeamId()), a.getTeamId(), null, "account team");
            }
        }

        for (CrmAssignmentRule rule : ruleRepo.findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(tenantId)) {
            if (matches(rule, lead)) {
                return new Decision(pick(tenantId, rule), rule.getTargetTeamId(), rule.getId(), "rule " + rule.getName());
            }
        }

        Long segmentTeam = segmentService.defaultTeam(tenantId, lead.getSegment());
        if (segmentTeam != null) {
            return new Decision(leastLoaded(tenantId, segmentTeam), segmentTeam, null, "segment " + lead.getSegment());
        }
        return new Decision(fallbackUserId, teamDirectory.primaryTeamOf(tenantId, fallbackUserId).orElse(null), null, "creator");
    }

    static boolean matches(CrmAssignmentRule rule, CrmLead lead) {
        JsonNode attrs = lead.getAttrs();
        if (!same(rule.getSegment(), lead.getSegment())
                || !same(rule.getCountry(), lead.getCountry())
                || !same(rule.getSource(), lead.getSource())
                || !same(rule.getInquiryType(), text(attrs, "inquiryType"))
                || !same(rule.getEventType(), text(attrs, "eventType"))) {
            return false;
        }
        if (rule.getMinPax() == null && rule.getMaxPax() == null) {
            return true;
        }
        Integer pax = attrs != null && attrs.hasNonNull("pax") && attrs.get("pax").canConvertToInt() ? attrs.get("pax").asInt() : null;
        if (pax == null) {
            return false;
        }
        return (rule.getMinPax() == null || pax >= rule.getMinPax()) && (rule.getMaxPax() == null || pax <= rule.getMaxPax());
    }

    private Long pick(Long tenantId, CrmAssignmentRule rule) {
        return switch (rule.getStrategy()) {
            case QUEUE -> null;
            case FIXED_USER -> rule.getFixedUserId();
            case LEAST_LOADED -> leastLoaded(tenantId, rule.getTargetTeamId());
            case ROUND_ROBIN -> roundRobin(tenantId, rule.getId());
        };
    }

    private Long roundRobin(Long tenantId, Long ruleId) {
        CrmAssignmentRule rule = ruleRepo.lockById(ruleId).orElseThrow();
        List<Long> members = teamDirectory.assignableMembers(tenantId, rule.getTargetTeamId());
        if (members.isEmpty()) {
            return null;
        }
        Long last = rule.getLastAssignedUserId();
        Long next = members.stream().filter(id -> last == null || id > last).findFirst().orElse(members.get(0));
        rule.setLastAssignedUserId(next);
        ruleRepo.save(rule);
        return next;
    }

    private Long leastLoaded(Long tenantId, Long teamId) {
        List<Long> members = teamDirectory.assignableMembers(tenantId, teamId);
        if (members.isEmpty()) {
            return null;
        }
        Map<Long, Long> load = new HashMap<>();
        for (Object[] row : leadRepo.countByOwner(tenantId, members, OPEN_STATUSES)) {
            load.put((Long) row[0], (Long) row[1]);
        }
        Long best = null;
        long bestLoad = Long.MAX_VALUE;
        for (Long id : members) {
            long l = load.getOrDefault(id, 0L);
            if (l < bestLoad) {
                best = id;
                bestLoad = l;
            }
        }
        return best;
    }

    private Optional<CrmAccount> matchAccount(Long tenantId, CrmLead lead) {
        if (StringUtils.hasText(lead.getEmail())) {
            Optional<CrmAccount> byContact = contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(tenantId, lead.getEmail().trim())
                    .map(c -> c.getAccountId())
                    .flatMap(id -> accountRepo.findByIdAndTenantId(id, tenantId))
                    .filter(a -> Boolean.TRUE.equals(a.getActive()));
            if (byContact.isPresent()) {
                return byContact;
            }
        }
        if (StringUtils.hasText(lead.getCompanyName())) {
            return accountRepo.findFirstByTenantIdAndNameIgnoreCaseAndActiveTrueOrderByIdAsc(tenantId, lead.getCompanyName().trim());
        }
        return Optional.empty();
    }

    private static boolean same(String criterion, String value) {
        return !StringUtils.hasText(criterion) || (value != null && criterion.trim().equalsIgnoreCase(value.trim()));
    }

    private static String text(JsonNode attrs, String field) {
        return attrs != null && attrs.hasNonNull(field) ? attrs.get(field).asText() : null;
    }
}
