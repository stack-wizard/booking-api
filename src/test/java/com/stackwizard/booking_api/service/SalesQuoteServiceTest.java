package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.PortalAccessToken;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteApproval;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.SalesQuoteVersion;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.SalesQuoteApprovalRepository;
import com.stackwizard.booking_api.repository.SalesQuoteLineRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.repository.SalesQuoteVersionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
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
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesQuoteServiceTest {

    @Mock SalesQuoteRepository quoteRepo;
    @Mock SalesQuoteLineRepository lineRepo;
    @Mock SalesQuoteVersionRepository versionRepo;
    @Mock SalesQuoteApprovalRepository approvalRepo;
    @Mock EventService eventService;
    @Mock EventFunctionRepository functionRepo;
    @Mock EventFunctionItemRepository itemRepo;
    @Mock EventReservationSync reservationSync;
    @Mock ProductRepository productRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock PortalTokenService portalTokenService;
    @Mock CrmAlertService alertService;
    @Mock CrmAccessContext accessContext;
    @Mock PlatformTransactionManager transactionManager;

    SalesQuoteService service;
    Event event;
    List<SalesQuoteLine> lines;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new SalesQuoteService(quoteRepo, lineRepo, versionRepo, approvalRepo, eventService, functionRepo, itemRepo,
                reservationSync, productRepo, accountRepo, portalTokenService, alertService, accessContext,
                transactionManager, new BigDecimal("10"), 14);
        event = Event.builder().id(5L).tenantId(1L).accountId(2L).ownerUserId(7L).name("Pharma")
                .status(Event.Status.TENTATIVE).currency("EUR")
                .dateFrom(LocalDate.now().plusDays(30)).dateTo(LocalDate.now().plusDays(31)).build();
        when(eventService.requireEvent(5L)).thenReturn(event);
        when(eventService.requireEditable(5L)).thenReturn(event);
        when(eventService.findForSystem(1L, 5L)).thenReturn(Optional.of(event));
        when(quoteRepo.save(any())).thenAnswer(inv -> {
            SalesQuote q = inv.getArgument(0);
            if (q.getId() == null) {
                q.setId(50L);
            }
            return q;
        });
        lines = new ArrayList<>();
        when(lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(1L, 50L)).thenAnswer(inv -> lines);
        when(lineRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(accessContext.currentUserId()).thenReturn(7L);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void buildLinesGroupsRentCateringAndAvAtOfferedRates() {
        EventFunction plenary = function(1L, EventFunction.FunctionType.PLENARY);
        EventFunction lunch = function(2L, EventFunction.FunctionType.LUNCH);
        Reservation rent = Reservation.builder().productId(10L).eventFunctionId(1L).qty(1).uom("DAY")
                .unitPrice(new BigDecimal("1200")).build();
        EventFunctionItem projector = EventFunctionItem.builder().eventFunctionId(1L).productId(11L).qty(1)
                .unitPrice(new BigDecimal("60")).grossAmount(new BigDecimal("60")).build();
        EventFunctionItem meal = EventFunctionItem.builder().eventFunctionId(2L).productId(12L).qty(40)
                .unitPrice(new BigDecimal("28")).grossAmount(new BigDecimal("1008")).build();
        Map<Long, Product> products = Map.of(
                10L, product(10L, "Hall A rent", null),
                11L, product(11L, "Projector", SalesQuoteLine.Group.AV),
                12L, product(12L, "Business lunch", null));

        List<SalesQuoteLine> built = SalesQuoteService.buildLines(1L, List.of(plenary, lunch),
                Map.of(1L, rent), Map.of(1L, List.of(projector), 2L, List.of(meal)), products);

        assertThat(built).extracting(SalesQuoteLine::getLineGroup)
                .containsExactly(SalesQuoteLine.Group.MEETING, SalesQuoteLine.Group.AV, SalesQuoteLine.Group.F_AND_B);
        SalesQuoteLine lunchLine = built.get(2);
        assertThat(lunchLine.getBaseRate()).isEqualByComparingTo("28.00");
        assertThat(lunchLine.getOfferedRate()).isEqualByComparingTo("25.20");
        assertThat(lunchLine.getAmountOffered()).isEqualByComparingTo("1008.00");
    }

    @Test
    void recalculateComputesDiscountAndContainedVat() {
        SalesQuote quote = new SalesQuote();
        List<SalesQuoteLine> rows = List.of(
                line(SalesQuoteLine.Group.MEETING, 1, "1000", "1000", "25"),
                line(SalesQuoteLine.Group.F_AND_B, 10, "20", "18", "13"),
                line(SalesQuoteLine.Group.DISCOUNT, 1, "0", "-80", "25"));

        SalesQuoteService.recalculate(quote, rows);

        assertThat(quote.getTotalBase()).isEqualByComparingTo("1200.00");
        assertThat(quote.getTotalOffered()).isEqualByComparingTo("1100.00");
        assertThat(quote.getDiscountPercent()).isEqualByComparingTo("8.3333");
        assertThat(quote.getTotalTax()).isEqualByComparingTo("204.71");
    }

    @Test
    void createSupersedesOpenQuotesAndNumbersPerYear() {
        SalesQuote old = quote(SalesQuote.Status.SENT, "0");
        old.setId(40L);
        when(quoteRepo.findByTenantIdAndEventIdAndStatusIn(eq(1L), eq(5L), any())).thenReturn(List.of(old));
        when(quoteRepo.countByNumberPrefix(eq(1L), any())).thenReturn(2L);
        EventFunction plenary = function(1L, EventFunction.FunctionType.PLENARY);
        when(functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(1L, 5L)).thenReturn(List.of(plenary));
        when(reservationSync.activeLines(1L, List.of(1L))).thenReturn(List.of(Reservation.builder().productId(10L)
                .eventFunctionId(1L).qty(1).uom("DAY").unitPrice(new BigDecimal("500")).build()));
        when(productRepo.findAllById(anyList())).thenReturn(List.of(product(10L, "Hall", null)));

        SalesQuote created = service.create(5L);

        assertThat(old.getStatus()).isEqualTo(SalesQuote.Status.SUPERSEDED);
        assertThat(created.getQuoteNumber()).isEqualTo("Q" + LocalDate.now().getYear() + "-0003");
        assertThat(created.getTotalOffered()).isEqualByComparingTo("500.00");
        assertThat(created.getStatus()).isEqualTo(SalesQuote.Status.DRAFT);
    }

    @Test
    void createRejectsEventWithAcceptedQuote() {
        when(quoteRepo.existsByTenantIdAndEventIdAndStatus(1L, 5L, SalesQuote.Status.ACCEPTED)).thenReturn(true);
        assertThatThrownBy(() -> service.create(5L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("accepted");
    }

    @Test
    void discountAboveThresholdNeedsApprovalBeforeSending() {
        SalesQuote quote = quote(SalesQuote.Status.DRAFT, "15");
        lines.add(line(SalesQuoteLine.Group.MEETING, 1, "1000", "850", "25"));

        assertThatThrownBy(() -> service.send(50L)).isInstanceOf(IllegalStateException.class).hasMessageContaining("request approval");

        service.requestApproval(50L, "Key account");
        assertThat(quote.getStatus()).isEqualTo(SalesQuote.Status.PENDING_APPROVAL);
        ArgumentCaptor<SalesQuoteApproval> approval = ArgumentCaptor.forClass(SalesQuoteApproval.class);
        verify(approvalRepo).save(approval.capture());
        assertThat(approval.getValue().getThresholdPercent()).isEqualByComparingTo("10");

        when(approvalRepo.findFirstByTenantIdAndQuoteIdAndStatus(1L, 50L, SalesQuoteApproval.Status.PENDING))
                .thenReturn(Optional.of(approval.getValue()));
        service.decideApproval(50L, true, null);
        assertThat(quote.getStatus()).isEqualTo(SalesQuote.Status.APPROVED);
        verify(accessContext).require(CrmPermission.QUOTE_APPROVE);

        PortalAccessToken token = PortalAccessToken.builder().token("abc").build();
        when(portalTokenService.ensureToken(event, 7L)).thenReturn(token);
        when(portalTokenService.portalUrl(token)).thenReturn("http://portal/abc");
        SalesDtos.QuoteView view = service.send(50L);

        assertThat(quote.getStatus()).isEqualTo(SalesQuote.Status.SENT);
        assertThat(quote.getVersion()).isEqualTo(1);
        assertThat(view.portalUrl()).isEqualTo("http://portal/abc");
        ArgumentCaptor<SalesQuoteVersion> version = ArgumentCaptor.forClass(SalesQuoteVersion.class);
        verify(versionRepo).save(version.capture());
        assertThat(version.getValue().getSnapshot().path("lines").size()).isEqualTo(1);
    }

    @Test
    void approvalDecisionNeedsManagerPermission() {
        quote(SalesQuote.Status.PENDING_APPROVAL, "15");
        doThrow(new IllegalStateException("forbidden")).when(accessContext).require(CrmPermission.QUOTE_APPROVE);
        assertThatThrownBy(() -> service.decideApproval(50L, true, null)).hasMessage("forbidden");
    }

    @Test
    void editingApprovedQuoteResetsToDraftAndDiscountLineIsNegative() {
        SalesQuote quote = quote(SalesQuote.Status.APPROVED, "0");
        lines.add(line(SalesQuoteLine.Group.MEETING, 1, "1000", "1000", "25"));

        SalesQuoteLine discount = service.addLine(50L, new SalesDtos.QuoteLineRequest(SalesQuoteLine.Group.DISCOUNT, null,
                "Loyalty", null, null, 1, null, new BigDecimal("50"), null, null));

        assertThat(discount.getOfferedRate()).isEqualByComparingTo("-50.00");
        assertThat(discount.getBaseRate()).isEqualByComparingTo("0");
        assertThat(quote.getStatus()).isEqualTo(SalesQuote.Status.DRAFT);
        assertThat(quote.getTotalOffered()).isEqualByComparingTo("950.00");
    }

    @Test
    void sentQuoteIsNotEditable() {
        quote(SalesQuote.Status.SENT, "0");
        assertThatThrownBy(() -> service.updateHeader(50L, new SalesDtos.QuoteHeaderRequest(null, "x", null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("revise");
    }

    @Test
    void clientDecisionAcceptsAndAlertsOwner() {
        SalesQuote quote = quote(SalesQuote.Status.SENT, "0");
        quote.setVersion(2);

        service.applyDecision(quote, true, "Ana", "OK", true);

        assertThat(quote.getStatus()).isEqualTo(SalesQuote.Status.ACCEPTED);
        assertThat(quote.getDecidedByName()).isEqualTo("Ana");
        ArgumentCaptor<CrmAlert> alert = ArgumentCaptor.forClass(CrmAlert.class);
        verify(alertService).raise(alert.capture());
        assertThat(alert.getValue().getKind()).isEqualTo(CrmAlert.Kind.QUOTE_DECIDED);
        assertThat(alert.getValue().getAssignedTo()).isEqualTo(7L);
        assertThat(alert.getValue().getDedupeKey()).isEqualTo("QUOTE_DECIDED:50:2");
    }

    @Test
    void expiredQuoteCannotBeAccepted() {
        SalesQuote quote = quote(SalesQuote.Status.SENT, "0");
        quote.setValidUntil(LocalDate.now().minusDays(1));
        assertThatThrownBy(() -> service.applyDecision(quote, true, "Ana", null, true))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("expired");
        verify(alertService, never()).raise(any());
    }

    private SalesQuote quote(SalesQuote.Status status, String discount) {
        SalesQuote quote = SalesQuote.builder().id(50L).tenantId(1L).eventId(5L).accountId(2L).quoteNumber("Q-1")
                .status(status).currency("EUR").version(0).validUntil(LocalDate.now().plusDays(10))
                .totalBase(BigDecimal.ZERO).totalOffered(BigDecimal.ZERO).totalTax(BigDecimal.ZERO)
                .discountPercent(new BigDecimal(discount)).build();
        when(quoteRepo.findByIdAndTenantId(50L, 1L)).thenReturn(Optional.of(quote));
        return quote;
    }

    private static EventFunction function(Long id, EventFunction.FunctionType type) {
        LocalDateTime start = LocalDate.of(2026, 11, 10).atTime(9, 0);
        return EventFunction.builder().id(id).tenantId(1L).eventId(5L).functionType(type).name(type.name())
                .startsAt(start).endsAt(start.plusHours(8)).build();
    }

    private static Product product(Long id, String name, SalesQuoteLine.Group group) {
        return Product.builder().id(id).tenantId(1L).name(name).defaultUom("UNIT").salesGroup(group)
                .tax1Percent(new BigDecimal("25")).tax2Percent(BigDecimal.ZERO).build();
    }

    private static SalesQuoteLine line(SalesQuoteLine.Group group, int qty, String base, String offered, String tax) {
        return SalesQuoteLine.builder().tenantId(1L).quoteId(50L).lineGroup(group).description(group.name()).qty(qty)
                .baseRate(new BigDecimal(base)).offeredRate(new BigDecimal(offered))
                .tax1Percent(new BigDecimal(tax)).tax2Percent(BigDecimal.ZERO)
                .amountBase(new BigDecimal(base).multiply(BigDecimal.valueOf(qty)))
                .amountOffered(new BigDecimal(offered).multiply(BigDecimal.valueOf(qty)))
                .displayOrder(0).build();
    }
}
