package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.CatalogInquiryRequest;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmPipelineRepository;
import com.stackwizard.booking_api.repository.CrmPipelineStageRepository;
import com.stackwizard.booking_api.repository.CrmStageTransitionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CatalogInquiryServiceTest {

    @Mock CrmLeadRepository leadRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmPipelineRepository pipelineRepo;
    @Mock CrmPipelineStageRepository stageRepo;
    @Mock CrmOpportunityRepository opportunityRepo;
    @Mock CrmStageTransitionRepository transitionRepo;
    @Mock CrmAccessContext accessContext;
    @Mock CrmTeamDirectory teamDirectory;
    @Mock CrmSegmentService segmentService;
    @Mock CrmLeadAssignmentService assignmentService;

    CatalogInquiryService service;

    @BeforeEach
    void setUp() {
        TenantContext.setScope(1L, null);
        CrmLeadService leadService = new CrmLeadService(
                leadRepo, accountRepo, contactRepo, pipelineRepo, stageRepo, opportunityRepo, transitionRepo,
                accessContext, teamDirectory, segmentService, assignmentService);
        service = new CatalogInquiryService(leadService, leadRepo);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void m2mCreatesWebsiteLeadAssignedByRulesWithoutUser() {
        authenticate(Map.of("token_use", "m2m", "scope", "booking.api"), "ROLE_API");
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(1L, "WEBSITE", "cms-42")).thenReturn(Optional.empty());
        when(assignmentService.assign(any(), isNull())).thenAnswer(inv -> {
            CrmLead lead = inv.getArgument(0);
            lead.setTeamId(7L);
            lead.setOwnerUserId(70L);
            return new CrmLeadAssignmentService.Decision(70L, 7L, 3L, "rule MICE");
        });
        when(leadRepo.save(any())).thenAnswer(inv -> {
            CrmLead lead = inv.getArgument(0);
            lead.setId(99L);
            return lead;
        });

        CatalogInquiryService.Result result = service.submit(request("cms-42"));

        assertThat(result.created()).isTrue();
        ArgumentCaptor<CrmLead> saved = ArgumentCaptor.forClass(CrmLead.class);
        verify(leadRepo).save(saved.capture());
        CrmLead lead = saved.getValue();
        assertThat(lead.getId()).isEqualTo(99L);
        assertThat(lead.getTenantId()).isEqualTo(1L);
        assertThat(lead.getSource()).isEqualTo("WEBSITE");
        assertThat(lead.getExternalRef()).isEqualTo("cms-42");
        assertThat(lead.getStatus()).isEqualTo(CrmLead.Status.NEW);
        assertThat(lead.getCompanyName()).isEqualTo("Acme d.o.o.");
        assertThat(lead.getFirstName()).isEqualTo("Ana");
        assertThat(lead.getEmail()).isEqualTo("ana@acme.hr");
        assertThat(lead.getCountry()).isEqualTo("HR");
        assertThat(lead.getOwnerUserId()).isEqualTo(70L);
        assertThat(lead.getTeamId()).isEqualTo(7L);
        assertThat(lead.getDescription())
                .contains("Event type: CONFERENCE")
                .contains("Dates: 2026-11-10 - 2026-11-11 (2 days, full day)")
                .contains("Attendees: 120")
                .contains("- Grand Hall (THEATRE)")
                .contains("- [CATERING] Coffee break x 240 pax @ 6.5 EUR = 1560 EUR")
                .contains("Estimated total: 4560 EUR")
                .contains("Need a stage");

        JsonNode attrs = lead.getAttrs();
        assertThat(attrs.get("channel").asText()).isEqualTo("booking-cms");
        assertThat(attrs.get("inquiryType").asText()).isEqualTo("MICE");
        assertThat(attrs.get("eventType").asText()).isEqualTo("CONFERENCE");
        assertThat(attrs.get("pax").asInt()).isEqualTo(120);
        assertThat(attrs.get("startDate").asText()).isEqualTo("2026-11-10");
        assertThat(attrs.get("setupStyle").asText()).isEqualTo("THEATRE");
        JsonNode inquiry = attrs.get("inquiry");
        assertThat(inquiry.get("vatId").asText()).isEqualTo("HR12345678901");
        assertThat(inquiry.get("spaces").get(0).get("resourceId").asLong()).isEqualTo(11L);
        assertThat(inquiry.get("services").get(0).get("category").asText()).isEqualTo("CATERING");
        assertThat(inquiry.get("services").get(0).get("total").decimalValue()).isEqualByComparingTo("1560");
        assertThat(inquiry.get("cmsInquiryId").asText()).isEqualTo("cms-42");

        verify(assignmentService).assign(eq(lead), isNull());
        verifyNoInteractions(accessContext);
    }

    @Test
    void userJwtIsRejected() {
        authenticate(Map.of("sub", "21111111-1111-1111-1111-111111111111"), "ROLE_ADMIN");

        assertThatThrownBy(() -> service.submit(request("cms-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(leadRepo, never()).save(any());
    }

    @Test
    void roleApiWithoutM2mTokenIsRejected() {
        authenticate(Map.of("sub", "21111111-1111-1111-1111-111111111111"), "ROLE_API");

        assertThatThrownBy(() -> service.submit(request("cms-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
        verify(leadRepo, never()).save(any());
    }

    @Test
    void anonymousIsUnauthorized() {
        assertThatThrownBy(() -> service.submit(request("cms-1")))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verifyNoInteractions(leadRepo);
    }

    @Test
    void replayOfSameCmsInquiryReturnsExistingLead() {
        authenticate(Map.of("token_use", "m2m", "scope", "booking.api"), "ROLE_API");
        CrmLead existing = CrmLead.builder().id(55L).tenantId(1L).source("WEBSITE").externalRef("cms-42")
                .status(CrmLead.Status.WORKING).build();
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(1L, "WEBSITE", "cms-42")).thenReturn(Optional.of(existing));

        CatalogInquiryService.Result result = service.submit(request("  cms-42 "));

        assertThat(result.created()).isFalse();
        assertThat(result.lead()).isSameAs(existing);
        verify(leadRepo, never()).save(any());
        verifyNoInteractions(assignmentService);
    }

    @Test
    void userLeadCreateStillUsesCreatorFallback() {
        when(accessContext.currentUserId()).thenReturn(10L);
        when(leadRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        CrmLeadService leadService = new CrmLeadService(
                leadRepo, accountRepo, contactRepo, pipelineRepo, stageRepo, opportunityRepo, transitionRepo,
                accessContext, teamDirectory, segmentService, assignmentService);

        leadService.create(CrmLead.builder().companyName("Acme").build());

        verify(accessContext).require(any());
        verify(assignmentService).assign(any(), eq(10L));
    }

    static CatalogInquiryRequest request(String cmsInquiryId) {
        return CatalogInquiryRequest.builder()
                .companyName("Acme d.o.o.")
                .vatId("HR12345678901")
                .firstName("Ana")
                .lastName("Horvat")
                .email("ana@acme.hr")
                .phone("+385 1 2345 678")
                .country("hr")
                .eventType(CatalogInquiryRequest.EventType.CONFERENCE)
                .dateFrom(LocalDate.of(2026, 11, 10))
                .dateTo(LocalDate.of(2026, 11, 11))
                .attendees(120)
                .dayFormat(CatalogInquiryRequest.DayFormat.FULL_DAY)
                .spaces(List.of(CatalogInquiryRequest.Space.builder()
                        .resourceId(11L).name("Grand Hall").setupStyle("THEATRE").build()))
                .services(List.of(CatalogInquiryRequest.Service.builder()
                        .productId(21L).name("Coffee break").category(CatalogInquiryRequest.ServiceCategory.CATERING)
                        .quantity(new BigDecimal("240")).unit("pax")
                        .unitPrice(new BigDecimal("6.50")).total(new BigDecimal("1560.00")).build()))
                .estimatedTotal(new BigDecimal("4560.00"))
                .currency("EUR")
                .notes("Need a stage")
                .sourceUrl("https://example.com/mice")
                .cmsInquiryId(cmsInquiryId)
                .build();
    }

    private static void authenticate(Map<String, Object> claims, String authority) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(3600), Map.of("alg", "none"), claims);
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(authority))));
    }
}
