package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.CrmLeadConvertRequest;
import com.stackwizard.booking_api.dto.CrmStageChangeRequest;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.CrmPipeline;
import com.stackwizard.booking_api.model.CrmPipelineStage;
import com.stackwizard.booking_api.model.CrmStageRequirement;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.CrmPipelineRepository;
import com.stackwizard.booking_api.repository.CrmPipelineStageRepository;
import com.stackwizard.booking_api.repository.CrmStageRequirementRepository;
import com.stackwizard.booking_api.repository.CrmStageTransitionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.CrmScope;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrmOpportunityLeadServiceTest {

    @Mock CrmOpportunityRepository opportunityRepo;
    @Mock CrmPipelineStageRepository stageRepo;
    @Mock CrmStageRequirementRepository requirementRepo;
    @Mock CrmOutcomeReasonRepository outcomeRepo;
    @Mock CrmStageTransitionRepository transitionRepo;
    @Mock CrmLeadRepository leadRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmPipelineRepository pipelineRepo;
    @Mock CrmAccessContext accessContext;

    ObjectMapper objectMapper = new ObjectMapper();
    CrmOpportunityService opportunityService;
    CrmLeadService leadService;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        opportunityService = new CrmOpportunityService(
                opportunityRepo, stageRepo, requirementRepo, outcomeRepo, transitionRepo, accountRepo, contactRepo,
                accessContext);
        leadService = new CrmLeadService(
                leadRepo, accountRepo, contactRepo, pipelineRepo, stageRepo, opportunityRepo, transitionRepo,
                accessContext);
        doNothing().when(accessContext).require(any());
        lenient().when(accessContext.currentUserId()).thenReturn(10L);
        lenient().when(accessContext.scope()).thenReturn(CrmScope.ALL);
        lenient().when(accessContext.teamUserIds()).thenReturn(Set.of(10L));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void changeStageFailsWhenRequirementNotMet() {
        CrmOpportunity opp = CrmOpportunity.builder()
                .id(5L).tenantId(1L).name("Opp").accountId(1L).pipelineId(2L).stageId(3L)
                .status(CrmOpportunity.Status.OPEN).currency("EUR").ownerUserId(10L)
                .attrs(objectMapper.createObjectNode())
                .build();
        when(opportunityRepo.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(opp));
        when(stageRepo.findByIdAndTenantId(4L, 1L)).thenReturn(Optional.of(CrmPipelineStage.builder()
                .id(4L).tenantId(1L).pipelineId(2L).code("PROP").name("Proposal")
                .displayOrder(2).probability(BigDecimal.TEN).stageKind(CrmPipelineStage.StageKind.OPEN)
                .build()));
        when(requirementRepo.findByTenantIdAndStageId(1L, 4L)).thenReturn(List.of(
                CrmStageRequirement.builder()
                        .fieldPath("amount")
                        .requirement(CrmStageRequirement.Requirement.REQUIRED)
                        .message("Amount is required")
                        .build()
        ));

        CrmStageChangeRequest request = new CrmStageChangeRequest();
        request.setStageId(4L);

        assertThatThrownBy(() -> opportunityService.changeStage(5L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Amount is required");
    }

    @Test
    void changeStageToWonRequiresOutcomeReason() {
        CrmOpportunity opp = CrmOpportunity.builder()
                .id(5L).tenantId(1L).name("Opp").accountId(1L).pipelineId(2L).stageId(3L)
                .amount(BigDecimal.ONE).status(CrmOpportunity.Status.OPEN).currency("EUR").ownerUserId(10L)
                .attrs(objectMapper.createObjectNode())
                .build();
        when(opportunityRepo.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(opp));
        when(stageRepo.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(CrmPipelineStage.builder()
                .id(9L).tenantId(1L).pipelineId(2L).code("WON").name("Won")
                .displayOrder(9).probability(BigDecimal.valueOf(100)).stageKind(CrmPipelineStage.StageKind.WON)
                .build()));
        when(requirementRepo.findByTenantIdAndStageId(1L, 9L)).thenReturn(List.of());

        CrmStageChangeRequest request = new CrmStageChangeRequest();
        request.setStageId(9L);

        assertThatThrownBy(() -> opportunityService.changeStage(5L, request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcomeReasonId");
    }

    @Test
    void movingClosedOpportunityToOpenStageReopensIt() {
        CrmOpportunity opp = CrmOpportunity.builder()
                .id(5L).tenantId(1L).name("Opp").accountId(1L).pipelineId(2L).stageId(9L)
                .amount(BigDecimal.ONE).status(CrmOpportunity.Status.WON).outcomeReasonId(11L)
                .closedAt(java.time.OffsetDateTime.now()).currency("EUR").ownerUserId(10L)
                .attrs(objectMapper.createObjectNode())
                .build();
        when(opportunityRepo.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(opp));
        when(stageRepo.findByIdAndTenantId(3L, 1L)).thenReturn(Optional.of(CrmPipelineStage.builder()
                .id(3L).tenantId(1L).pipelineId(2L).code("NEW").name("New")
                .displayOrder(1).probability(BigDecimal.TEN).stageKind(CrmPipelineStage.StageKind.OPEN)
                .build()));
        when(requirementRepo.findByTenantIdAndStageId(1L, 3L)).thenReturn(List.of());
        when(opportunityRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CrmStageChangeRequest request = new CrmStageChangeRequest();
        request.setStageId(3L);
        CrmOpportunity reopened = opportunityService.changeStage(5L, request);

        assertThat(reopened.getStatus()).isEqualTo(CrmOpportunity.Status.OPEN);
        assertThat(reopened.getOutcomeReasonId()).isNull();
        assertThat(reopened.getClosedAt()).isNull();
    }

    @Test
    void convertLeadIsIdempotentConflict() {
        when(leadRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(CrmLead.builder()
                .id(7L).tenantId(1L).status(CrmLead.Status.CONVERTED).build()));

        assertThatThrownBy(() -> leadService.convert(7L, new CrmLeadConvertRequest()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already converted");
    }

    @Test
    void convertLeadCreatesOpportunityAndTransition() {
        when(leadRepo.findByIdAndTenantId(7L, 1L)).thenReturn(Optional.of(CrmLead.builder()
                .id(7L).tenantId(1L).status(CrmLead.Status.QUALIFIED)
                .companyName("Acme").firstName("Ana").lastName("Anic")
                .email("ana@acme.test").ownerUserId(10L)
                .attrs(objectMapper.createObjectNode())
                .build()));
        when(accountRepo.save(any())).thenAnswer(inv -> {
            var a = inv.getArgument(0, com.stackwizard.booking_api.model.CrmAccount.class);
            a.setId(100L);
            return a;
        });
        when(contactRepo.save(any())).thenAnswer(inv -> {
            var c = inv.getArgument(0, com.stackwizard.booking_api.model.CrmContact.class);
            c.setId(200L);
            return c;
        });
        when(pipelineRepo.findByTenantIdAndIsDefaultTrue(1L)).thenReturn(Optional.of(
                CrmPipeline.builder().id(3L).tenantId(1L).code("DEFAULT").name("Default").isDefault(true).active(true).build()));
        when(stageRepo.findFirstByTenantIdAndPipelineIdOrderByDisplayOrderAsc(1L, 3L)).thenReturn(Optional.of(
                CrmPipelineStage.builder().id(4L).tenantId(1L).pipelineId(3L).code("NEW").name("New")
                        .displayOrder(1).probability(BigDecimal.ZERO).stageKind(CrmPipelineStage.StageKind.OPEN).build()));
        when(opportunityRepo.save(any())).thenAnswer(inv -> {
            var o = inv.getArgument(0, CrmOpportunity.class);
            o.setId(300L);
            return o;
        });
        when(leadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CrmLead converted = leadService.convert(7L, new CrmLeadConvertRequest());

        assertThat(converted.getStatus()).isEqualTo(CrmLead.Status.CONVERTED);
        assertThat(converted.getConvertedOpportunityId()).isEqualTo(300L);
        verify(transitionRepo).save(any());
        verify(accessContext).require(CrmPermission.OPPORTUNITY_WRITE);
    }
}
