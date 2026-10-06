package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAssignmentRule;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmAssignmentRuleRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrmLeadAssignmentServiceTest {

    @Mock CrmAssignmentRuleRepository ruleRepo;
    @Mock CrmLeadRepository leadRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmTeamDirectory teamDirectory;
    @Mock CrmSegmentService segmentService;

    ObjectMapper objectMapper = new ObjectMapper();
    CrmLeadAssignmentService service;

    @BeforeEach
    void setUp() {
        service = new CrmLeadAssignmentService(ruleRepo, leadRepo, accountRepo, contactRepo, teamDirectory, segmentService);
        when(contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(anyLong(), anyString())).thenReturn(Optional.empty());
        when(accountRepo.findFirstByTenantIdAndNameIgnoreCaseAndActiveTrueOrderByIdAsc(anyLong(), anyString())).thenReturn(Optional.empty());
        when(teamDirectory.primaryTeamOf(anyLong(), any())).thenReturn(Optional.empty());
        when(ruleRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(segmentService.defaultTeam(anyLong(), any())).thenReturn(null);
    }

    private CrmLead lead(int pax, String segment) {
        ObjectNode attrs = objectMapper.createObjectNode();
        attrs.put("pax", pax);
        attrs.put("inquiryType", "EVENT_CATERING");
        return CrmLead.builder().tenantId(1L).companyName("Acme").email("ana@acme.test").segment(segment).attrs(attrs).build();
    }

    private CrmAssignmentRule rule(long id, CrmAssignmentRule.Strategy strategy) {
        return CrmAssignmentRule.builder().id(id).tenantId(1L).name("R" + id).priority(10).active(true)
                .targetTeamId(5L).strategy(strategy).build();
    }

    @Test
    void existingAccountKeepsItsOwner() {
        when(contactRepo.findFirstByTenantIdAndEmailIgnoreCaseOrderByIdAsc(1L, "ana@acme.test"))
                .thenReturn(Optional.of(CrmContact.builder().id(3L).accountId(9L).build()));
        when(accountRepo.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(CrmAccount.builder()
                .id(9L).tenantId(1L).name("Acme").ownerUserId(42L).teamId(7L).segment("MICE").active(true).build()));
        when(teamDirectory.primaryTeamOf(1L, 42L)).thenReturn(Optional.of(7L));
        CrmLead lead = lead(50, null);

        CrmLeadAssignmentService.Decision d = service.assign(lead, 10L);

        assertThat(d.ownerUserId()).isEqualTo(42L);
        assertThat(lead.getTeamId()).isEqualTo(7L);
        assertThat(lead.getSegment()).isEqualTo("MICE");
        assertThat(lead.getAssignedAt()).isNotNull();
    }

    @Test
    void firstMatchingRuleWinsAndRoundRobinRotates() {
        CrmAssignmentRule tooSmall = rule(1, CrmAssignmentRule.Strategy.FIXED_USER);
        tooSmall.setMinPax(200);
        tooSmall.setFixedUserId(99L);
        CrmAssignmentRule mice = rule(2, CrmAssignmentRule.Strategy.ROUND_ROBIN);
        mice.setSegment("mice");
        mice.setLastAssignedUserId(20L);
        when(ruleRepo.findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(1L)).thenReturn(List.of(tooSmall, mice));
        when(ruleRepo.lockById(2L)).thenReturn(Optional.of(mice));
        when(teamDirectory.assignableMembers(1L, 5L)).thenReturn(List.of(10L, 20L, 30L));

        CrmLead first = lead(50, "MICE");
        service.assign(first, 1L);
        CrmLead second = lead(50, "MICE");
        service.assign(second, 1L);

        assertThat(first.getOwnerUserId()).isEqualTo(30L);
        assertThat(second.getOwnerUserId()).isEqualTo(10L);
        assertThat(first.getTeamId()).isEqualTo(5L);
        assertThat(first.getAssignmentRuleId()).isEqualTo(2L);
    }

    @Test
    void leastLoadedPicksRepWithFewestOpenLeads() {
        when(ruleRepo.findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(1L))
                .thenReturn(List.of(rule(3, CrmAssignmentRule.Strategy.LEAST_LOADED)));
        when(teamDirectory.assignableMembers(1L, 5L)).thenReturn(List.of(10L, 20L, 30L));
        when(leadRepo.countByOwner(eq(1L), any(), any())).thenReturn(List.of(new Object[]{10L, 4L}, new Object[]{20L, 1L}));

        CrmLead lead = lead(10, null);
        service.assign(lead, 1L);

        assertThat(lead.getOwnerUserId()).isEqualTo(30L);
    }

    @Test
    void queueRuleLeavesOwnerEmpty() {
        when(ruleRepo.findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(1L))
                .thenReturn(List.of(rule(4, CrmAssignmentRule.Strategy.QUEUE)));

        CrmLead lead = lead(10, null);
        service.assign(lead, 1L);

        assertThat(lead.getOwnerUserId()).isNull();
        assertThat(lead.getTeamId()).isEqualTo(5L);
    }

    @Test
    void noMatchFallsBackToCreator() {
        when(ruleRepo.findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(1L)).thenReturn(List.of());
        when(teamDirectory.primaryTeamOf(1L, 10L)).thenReturn(Optional.of(8L));

        CrmLead lead = lead(10, null);
        CrmLeadAssignmentService.Decision d = service.assign(lead, 10L);

        assertThat(d.reason()).isEqualTo("creator");
        assertThat(lead.getOwnerUserId()).isEqualTo(10L);
        assertThat(lead.getTeamId()).isEqualTo(8L);
        verify(ruleRepo).findByTenantIdAndActiveTrueOrderByPriorityAscIdAsc(1L);
    }

    @Test
    void paxRuleNeedsPax() {
        CrmAssignmentRule r = rule(5, CrmAssignmentRule.Strategy.QUEUE);
        r.setMaxPax(100);
        CrmLead noPax = CrmLead.builder().tenantId(1L).attrs(objectMapper.createObjectNode()).build();

        assertThat(CrmLeadAssignmentService.matches(r, noPax)).isFalse();
        assertThat(CrmLeadAssignmentService.matches(r, lead(80, null))).isTrue();
        assertThat(CrmLeadAssignmentService.matches(r, lead(120, null))).isFalse();
    }
}
