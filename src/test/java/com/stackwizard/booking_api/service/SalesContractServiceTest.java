package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.InvoiceCreateItemRequest;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.SalesContractDocumentRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesPaymentMilestoneRepository;
import com.stackwizard.booking_api.repository.SalesQuoteLineRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SalesContractServiceTest {

    @Mock SalesContractRepository contractRepo;
    @Mock SalesContractDocumentRepository documentRepo;
    @Mock SalesPaymentMilestoneRepository milestoneRepo;
    @Mock SalesQuoteRepository quoteRepo;
    @Mock SalesQuoteLineRepository quoteLineRepo;
    @Mock EventService eventService;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock ProductRepository productRepo;
    @Mock InvoiceService invoiceService;
    @Mock InvoiceRepository invoiceRepo;
    @Mock MediaStorageService mediaStorageService;
    @Mock CrmAccessContext accessContext;

    SalesContractService service;
    static final LocalDate TODAY = LocalDate.of(2026, 10, 6);
    Event event = Event.builder().id(5L).tenantId(1L).accountId(2L).name("Pharma")
            .dateFrom(LocalDate.of(2026, 11, 10)).dateTo(LocalDate.of(2026, 11, 11)).build();
    SalesContract contract = SalesContract.builder().id(9L).tenantId(1L).eventId(5L).quoteId(50L)
            .contractNumber("C2026-0001").status(SalesContract.Status.SIGNED).currency("EUR")
            .totalAmount(new BigDecimal("1000.00")).build();

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        service = new SalesContractService(contractRepo, documentRepo, milestoneRepo, quoteRepo, quoteLineRepo, eventService,
                accountRepo, contactRepo, productRepo, invoiceService, invoiceRepo, mediaStorageService, accessContext,
                new BigDecimal("30"));
        when(eventService.requireEvent(5L)).thenReturn(event);
        when(eventService.requireEditable(5L)).thenReturn(event);
        when(contractRepo.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(contract));
        when(contractRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invoiceRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void defaultPlanIsDepositThenFinal() {
        List<SalesPaymentMilestone> rows = SalesContractService.defaultMilestones(contract, event, new BigDecimal("30"), TODAY);
        assertThat(rows).extracting(SalesPaymentMilestone::getKind)
                .containsExactly(SalesPaymentMilestone.Kind.DEPOSIT, SalesPaymentMilestone.Kind.FINAL);
        assertThat(rows.get(0).getAmount()).isEqualByComparingTo("300.00");
        assertThat(rows.get(0).getDueDate()).isEqualTo(TODAY.plusDays(7));
        assertThat(rows.get(1).getAmount()).isEqualByComparingTo("700.00");
        assertThat(rows.get(1).getDueDate()).isEqualTo(event.getDateTo());
    }

    @Test
    void depositIsDueBeforeAnImminentEvent() {
        Event soon = Event.builder().dateFrom(TODAY.plusDays(3)).dateTo(TODAY.plusDays(3)).build();
        List<SalesPaymentMilestone> rows = SalesContractService.defaultMilestones(contract, soon, new BigDecimal("30"), TODAY);
        assertThat(rows.get(0).getDueDate()).isEqualTo(TODAY.plusDays(2));
    }

    @Test
    void milestonesMustAddUpAndFinalTakesTheRemainder() {
        List<SalesPaymentMilestone> rows = SalesContractService.normalizeMilestones(contract, List.of(
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.FINAL, null, TODAY.plusDays(40), null, null),
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.DEPOSIT, null, TODAY.plusDays(5), new BigDecimal("20"), null),
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.INTERIM, "Second", TODAY.plusDays(20), null, new BigDecimal("300"))));

        assertThat(rows).extracting(SalesPaymentMilestone::getKind).containsExactly(
                SalesPaymentMilestone.Kind.DEPOSIT, SalesPaymentMilestone.Kind.INTERIM, SalesPaymentMilestone.Kind.FINAL);
        assertThat(rows.get(2).getAmount()).isEqualByComparingTo("500.00");

        assertThatThrownBy(() -> SalesContractService.normalizeMilestones(contract, List.of(
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.DEPOSIT, null, TODAY, null, new BigDecimal("100")),
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.FINAL, null, TODAY, null, new BigDecimal("100")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("add up");
        assertThatThrownBy(() -> SalesContractService.normalizeMilestones(contract, List.of(
                new SalesDtos.MilestoneRequest(SalesPaymentMilestone.Kind.DEPOSIT, null, TODAY, null, new BigDecimal("100")))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FINAL");
    }

    @Test
    void finalInvoiceSpreadsDiscountLinesAsPercent() {
        List<InvoiceCreateItemRequest> items = SalesContractService.finalInvoiceItems(List.of(
                quoteLine(SalesQuoteLine.Group.MEETING, "800"),
                quoteLine(SalesQuoteLine.Group.F_AND_B, "200"),
                quoteLine(SalesQuoteLine.Group.DISCOUNT, "-100")));
        assertThat(items).hasSize(2);
        assertThat(items.get(0).getDiscountPercent()).isEqualByComparingTo("10");
        assertThat(items.get(0).getUnitPriceGross()).isEqualByComparingTo("800");
    }

    @Test
    void depositMilestoneIssuesDepositInvoiceWithReference() {
        SalesPaymentMilestone deposit = SalesPaymentMilestone.builder().id(70L).tenantId(1L).contractId(9L)
                .kind(SalesPaymentMilestone.Kind.DEPOSIT).dueDate(TODAY).amount(new BigDecimal("300.00"))
                .status(SalesPaymentMilestone.Status.PLANNED).displayOrder(0).build();
        when(milestoneRepo.findByIdAndTenantId(70L, 1L)).thenReturn(Optional.of(deposit));
        when(productRepo.findFirstByTenantIdAndProductTypeIgnoreCaseOrderByDisplayOrderAscIdAsc(1L, "DEPOSIT"))
                .thenReturn(Optional.of(Product.builder().id(99L).name("Deposit").build()));
        when(invoiceService.createManualDraft(any())).thenReturn(Invoice.builder().id(500L).tenantId(1L).build());

        service.invoiceMilestone(70L);

        ArgumentCaptor<InvoiceCreateRequest> request = ArgumentCaptor.forClass(InvoiceCreateRequest.class);
        verify(invoiceService).createManualDraft(request.capture());
        assertThat(request.getValue().getInvoiceType()).isEqualTo(InvoiceType.DEPOSIT);
        assertThat(request.getValue().getItems().get(0).getUnitPriceGross()).isEqualByComparingTo("300.00");
        assertThat(deposit.getStatus()).isEqualTo(SalesPaymentMilestone.Status.INVOICED);
        assertThat(deposit.getInvoiceId()).isEqualTo(500L);
    }

    @Test
    void draftContractCannotBeInvoiced() {
        contract.setStatus(SalesContract.Status.DRAFT);
        when(milestoneRepo.findByIdAndTenantId(70L, 1L)).thenReturn(Optional.of(SalesPaymentMilestone.builder()
                .id(70L).tenantId(1L).contractId(9L).kind(SalesPaymentMilestone.Kind.FINAL)
                .status(SalesPaymentMilestone.Status.PLANNED).build()));
        assertThatThrownBy(() -> service.invoiceMilestone(70L)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Send or sign");
    }

    @Test
    void contractNeedsAcceptedQuote() {
        when(quoteRepo.findFirstByTenantIdAndEventIdAndStatusOrderByIdDesc(1L, 5L, SalesQuote.Status.ACCEPTED))
                .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(5L, null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepted quote");
    }

    private static SalesQuoteLine quoteLine(SalesQuoteLine.Group group, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return SalesQuoteLine.builder().lineGroup(group).description(group.name()).qty(1)
                .baseRate(value.max(BigDecimal.ZERO)).offeredRate(value).amountOffered(value)
                .tax1Percent(new BigDecimal("25")).tax2Percent(BigDecimal.ZERO).build();
    }
}
