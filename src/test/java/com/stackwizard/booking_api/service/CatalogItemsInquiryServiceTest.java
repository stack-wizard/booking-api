package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.dto.CatalogItemsInquiryRequest;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.CrmLead;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.repository.CrmLeadRepository;
import com.stackwizard.booking_api.repository.CrmLookupRepository;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CatalogItemsInquiryServiceTest {
    @Mock CrmLeadService leadService;
    @Mock CrmLeadRepository leadRepo;
    @Mock CatalogQuoteService quoteService;
    @Mock CatalogViewService viewService;
    @Mock CrmLookupRepository lookupRepo;

    CatalogItemsInquiryService service;
    SalesCatalog mice = SalesCatalog.builder().id(1L).tenantId(2L).code("MICE").name("Meetings")
            .saleMode(SalesCatalog.SaleMode.INQUIRY).currency("EUR")
            .eventTypes(new java.util.ArrayList<>(List.of("CONFERENCE", "PARTY", "WEDDING"))).build();

    @BeforeEach
    void setUp() {
        TenantContext.setScope(2L, null);
        service = new CatalogItemsInquiryService(leadService, leadRepo, quoteService, viewService,
                new CatalogEventTypeService(lookupRepo));
        when(lookupRepo.findByTenantIdAndKindOrderByDisplayOrderAscIdAsc(2L, CrmLookup.Kind.EVENT_TYPE)).thenReturn(List.of(
                lookup("CONFERENCE", "Conference", true), lookup("PARTY", "Party", true), lookup("WEDDING", "Wedding", false)));
        CatalogViewService.Offer offer = mock(CatalogViewService.Offer.class);
        when(offer.items()).thenReturn(List.of(CatalogItem.builder().id(41L).itemKind(CatalogItem.Kind.RESOURCE)
                .resourceId(1386L).itemGroup("HALL").build()));
        when(viewService.offer(mice)).thenReturn(offer);
        when(quoteService.quote(any(), any())).thenReturn(new CatalogDtos.QuoteResponse("EUR", List.of(
                new CatalogDtos.QuoteLine(41L, "Galija", LocalDate.of(2027, 5, 13), 1, BigDecimal.ONE, "DAY",
                        new BigDecimal("1800.00"), new BigDecimal("1800.00"), new BigDecimal("25"),
                        new CatalogDtos.Rule("PRE_2027", PriceProfileDate.PeriodKind.SEASON, null))),
                new BigDecimal("1800.00"), new BigDecimal("1440.00"), false));
        when(leadService.createFromChannel(any())).thenAnswer(inv -> {
            CrmLead lead = inv.getArgument(0);
            lead.setId(99L);
            return lead;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    static CrmLookup lookup(String code, String label, boolean active) {
        return CrmLookup.builder().tenantId(2L).kind(CrmLookup.Kind.EVENT_TYPE).code(code).label(label).active(active).build();
    }

    static CatalogItemsInquiryRequest request(String cmsId) {
        return CatalogItemsInquiryRequest.builder()
                .companyName("Acme d.o.o.").firstName("Ana").lastName("Horvat").email("ana@acme.hr").country("hr")
                .eventType("conference")
                .dateFrom(LocalDate.of(2027, 5, 13)).dateTo(LocalDate.of(2027, 5, 13)).attendees(60)
                .items(List.of(CatalogItemsInquiryRequest.Item.builder().itemId(41L).setupStyle("THEATER").build()))
                .notes("Need a stage").cmsInquiryId(cmsId).build();
    }

    @Test
    void newInquiryIsPricedByBookingApiAndStoredInLead() {
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(2L, "WEBSITE", "cms-1")).thenReturn(Optional.empty());

        CatalogItemsInquiryService.Result result = service.submit(mice, request("cms-1"));

        assertThat(result.created()).isTrue();
        assertThat(result.quote().totalGross()).isEqualByComparingTo("1800.00");
        ArgumentCaptor<CrmLead> saved = ArgumentCaptor.forClass(CrmLead.class);
        verify(leadService).createFromChannel(saved.capture());
        CrmLead lead = saved.getValue();
        assertThat(lead.getSource()).isEqualTo("WEBSITE");
        assertThat(lead.getExternalRef()).isEqualTo("cms-1");
        assertThat(lead.getDescription())
                .contains("Website inquiry (Meetings)")
                .contains("- Galija, 2027-05-13: 1 DAY @ 1800 EUR = 1800 EUR")
                .contains("Estimated total: 1800 EUR")
                .contains("Setup: THEATER")
                .contains("Event type: Conference\n");
        JsonNode attrs = lead.getAttrs();
        assertThat(attrs.get("inquiryType").asText()).isEqualTo("MICE");
        assertThat(attrs.get("estimatedValue").decimalValue()).isEqualByComparingTo("1800");
        assertThat(attrs.get("setupStyle").asText()).isEqualTo("THEATER");
        assertThat(attrs.get("eventType").asText()).isEqualTo("CONFERENCE");
        assertThat(attrs.get("inquiry").get("eventTypeLabel").asText()).isEqualTo("Conference");
        JsonNode inquiry = attrs.get("inquiry");
        assertThat(inquiry.get("items").get(0).get("resourceId").asLong()).isEqualTo(1386L);
        assertThat(inquiry.get("items").get(0).get("group").asText()).isEqualTo("HALL");
        assertThat(inquiry.get("quote").get("lines").get(0).get("rule").get("season").asText()).isEqualTo("PRE_2027");
        assertThat(inquiry.get("country").asText()).isEqualTo("HR");
    }

    @Test
    void repeatedCmsInquiryIdReturnsExistingLeadWithoutPricing() {
        CrmLead existing = CrmLead.builder().id(55L).status(CrmLead.Status.WORKING).build();
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(2L, "WEBSITE", "cms-1")).thenReturn(Optional.of(existing));

        CatalogItemsInquiryService.Result result = service.submit(mice, request("cms-1"));

        assertThat(result.created()).isFalse();
        assertThat(result.lead()).isSameAs(existing);
        assertThat(result.quote()).isNull();
        verifyNoInteractions(quoteService);
        verify(leadService, never()).createFromChannel(any());
    }

    @Test
    void instantCatalogTakesNoInquiries() {
        SalesCatalog beach = SalesCatalog.builder().code("BEACH").saleMode(SalesCatalog.SaleMode.INSTANT).build();
        assertThatThrownBy(() -> service.submit(beach, request(null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("INSTANT");
    }

    @Test
    void eventTypeMustBeOneTheCatalogOffers() {
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(any(), any(), any())).thenReturn(Optional.empty());
        CatalogItemsInquiryRequest missing = request("cms-2");
        missing.setEventType(null);
        assertThatThrownBy(() -> service.submit(mice, missing))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventType is required; one of CONFERENCE, PARTY");
        CatalogItemsInquiryRequest inactive = request("cms-3");
        inactive.setEventType("WEDDING");
        assertThatThrownBy(() -> service.submit(mice, inactive))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("eventType WEDDING is not offered by catalog MICE");
        verify(leadService, never()).createFromChannel(any());
    }

    @Test
    void catalogWithoutEventTypesIgnoresTheField() {
        mice.setEventTypes(new java.util.ArrayList<>());
        when(leadRepo.findByTenantIdAndSourceAndExternalRef(any(), any(), any())).thenReturn(Optional.empty());

        service.submit(mice, request("cms-4"));

        ArgumentCaptor<CrmLead> saved = ArgumentCaptor.forClass(CrmLead.class);
        verify(leadService).createFromChannel(saved.capture());
        assertThat(saved.getValue().getAttrs().has("eventType")).isFalse();
        assertThat(saved.getValue().getDescription()).doesNotContain("Event type");
    }
}
