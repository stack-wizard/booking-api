package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CrmTeamDtos;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmReassignment;
import com.stackwizard.booking_api.model.CrmTeamMember;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmReassignmentRepository;
import com.stackwizard.booking_api.repository.CrmTeamMemberRepository;
import com.stackwizard.booking_api.repository.CrmTeamRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmScope;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CrmReassignmentServiceTest {

    @Mock CrmLeadRepository leadRepo;
    @Mock CrmOpportunityRepository opportunityRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock EventRepository eventRepo;
    @Mock CrmTeamMemberRepository memberRepo;
    @Mock CrmTeamRepository teamRepo;
    @Mock CrmReassignmentRepository reassignmentRepo;
    @Mock CrmTeamDirectory teamDirectory;
    @Mock CrmAccessContext accessContext;

    CrmReassignmentService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new CrmReassignmentService(leadRepo, opportunityRepo, accountRepo, eventRepo, memberRepo, teamRepo,
                reassignmentRepo, teamDirectory, accessContext);
        when(accessContext.currentUserId()).thenReturn(100L);
        when(accessContext.scope()).thenReturn(CrmScope.TEAM);
        when(accessContext.teamUserIds()).thenReturn(Set.of(100L, 101L, 102L));
        when(teamDirectory.requireUser(anyLong(), anyLong())).thenReturn(new AppUser());
        when(teamDirectory.resolveTeam(1L, 102L, null, null)).thenReturn(5L);
        when(reassignmentRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accountRepo.findByTenantIdAndOwnerUserIdAndActiveTrue(1L, 101L)).thenReturn(List.of());
        when(eventRepo.findByTenantIdAndOwnerUserIdAndStatusInAndDateToGreaterThanEqual(any(), any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void leadMovesOpenWorkOfMemberAndEndsMembership() {
        CrmLead lead = CrmLead.builder().id(1L).tenantId(1L).ownerUserId(101L).teamId(4L).status(CrmLead.Status.NEW).build();
        CrmOpportunity opp = CrmOpportunity.builder().id(2L).tenantId(1L).ownerUserId(101L).status(CrmOpportunity.Status.OPEN).build();
        CrmTeamMember membership = CrmTeamMember.builder().id(9L).tenantId(1L).teamId(4L).appUserId(101L)
                .validFrom(LocalDate.now().minusYears(1)).build();
        when(leadRepo.findByTenantIdAndOwnerUserIdAndStatusIn(1L, 101L, CrmLeadAssignmentService.OPEN_STATUSES)).thenReturn(List.of(lead));
        when(opportunityRepo.findByTenantIdAndOwnerUserIdAndStatus(1L, 101L, CrmOpportunity.Status.OPEN)).thenReturn(List.of(opp));
        when(memberRepo.findByTenantIdAndAppUserIdAndValidToIsNull(1L, 101L)).thenReturn(List.of(membership));

        CrmReassignment log = service.reassign(new CrmTeamDtos.ReassignRequest(101L, 102L, null,
                null, null, null, null, true, "left the company"));

        assertThat(lead.getOwnerUserId()).isEqualTo(102L);
        assertThat(lead.getTeamId()).isEqualTo(5L);
        assertThat(opp.getOwnerUserId()).isEqualTo(102L);
        assertThat(membership.getValidTo()).isEqualTo(LocalDate.now());
        assertThat(log.getCounts().get("leads").asInt()).isEqualTo(1);
        assertThat(log.getCounts().get("opportunities").asInt()).isEqualTo(1);
        assertThat(log.getCounts().get("endedMemberships").asInt()).isEqualTo(1);
        verify(memberRepo).save(membership);
    }

    @Test
    void repCannotReassignSomeoneOutsideLedTeams() {
        assertThatThrownBy(() -> service.reassign(new CrmTeamDtos.ReassignRequest(555L, 102L, null,
                null, null, null, null, false, null)))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void queueOnlyAcceptsLeads() {
        assertThatThrownBy(() -> service.reassign(new CrmTeamDtos.ReassignRequest(101L, null, 5L,
                true, true, false, false, false, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
