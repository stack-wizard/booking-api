package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.InvoiceCreateItemRequest;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceSequence;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.OperaPostingStatus;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.InvoiceSequenceRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
class InvoiceServiceTest {

    @Mock
    private InvoiceRepository invoiceRepo;
    @Mock
    private InvoiceItemRepository invoiceItemRepo;
    @Mock
    private InvoicePaymentAllocationRepository allocationRepo;
    @Mock
    private InvoiceSequenceRepository sequenceRepo;
    @Mock
    private PaymentTransactionService paymentTransactionService;
    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private ProductRepository productRepo;
    @Mock
    private PriceListEntryRepository priceListRepo;
    @Mock
    private FiscalBusinessPremiseService fiscalBusinessPremiseService;
    @Mock
    private FiscalCashRegisterService fiscalCashRegisterService;
    @Mock
    private AppUserRepository appUserRepo;

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceService(
                invoiceRepo,
                invoiceItemRepo,
                allocationRepo,
                sequenceRepo,
                paymentTransactionService,
                requestRepo,
                reservationRepo,
                productRepo,
                priceListRepo,
                fiscalBusinessPremiseService,
                fiscalCashRegisterService,
                appUserRepo
        );
    }

    @Test
    void createDepositInvoiceFallsBackToReservationContactData() {
        Long tenantId = 1L;
        Long requestId = 118L;
        PaymentIntent paymentIntent = PaymentIntent.builder()
                .id(501L)
                .tenantId(tenantId)
                .reservationRequestId(requestId)
                .currency("EUR")
                .amount(new BigDecimal("50.00"))
                .status("PAID")
                .build();
        ReservationRequest request = ReservationRequest.builder()
                .id(requestId)
                .tenantId(tenantId)
                .customerName(null)
                .customerEmail(null)
                .customerPhone(null)
                .build();
        Reservation reservation = Reservation.builder()
                .id(4178L)
                .request(request)
                .customerName("John Doe")
                .customerEmail("john@example.com")
                .customerPhone("+38591111222")
                .build();
        Product depositProduct = Product.builder()
                .id(91L)
                .tenantId(tenantId)
                .name("Deposit")
                .productType("DEPOSIT")
                .tax1Percent(new BigDecimal("25"))
                .tax2Percent(BigDecimal.ZERO)
                .build();
        InvoiceSequence sequence = InvoiceSequence.builder()
                .tenantId(tenantId)
                .invoiceType("DEPOSIT")
                .invoiceYear(LocalDate.now().getYear())
                .lastNumber(0)
                .build();
        PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                .id(700L)
                .tenantId(tenantId)
                .paymentIntentId(paymentIntent.getId())
                .reservationRequestId(requestId)
                .transactionType("CHARGE")
                .status("POSTED")
                .paymentType("CARD")
                .currency("EUR")
                .amount(new BigDecimal("50.00"))
                .build();

        when(invoiceRepo.findByReferenceTableAndReferenceId("payment_intent", paymentIntent.getId()))
                .thenReturn(Optional.empty());
        when(requestRepo.findById(requestId)).thenReturn(Optional.of(request));
        when(reservationRepo.findByRequestId(requestId)).thenReturn(List.of(reservation));
        when(productRepo.findFirstByTenantIdAndProductTypeIgnoreCaseOrderByDisplayOrderAscIdAsc(tenantId, "DEPOSIT"))
                .thenReturn(Optional.of(depositProduct));
        when(sequenceRepo.findForUpdate(tenantId, "DEPOSIT", LocalDate.now().getYear()))
                .thenReturn(Optional.of(sequence));
        when(sequenceRepo.save(any(InvoiceSequence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(900L);
            }
            return invoice;
        });
        when(invoiceItemRepo.save(any(InvoiceItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(paymentTransactionService.ensureForPaidIntent(paymentIntent)).thenReturn(paymentTransaction);
        when(allocationRepo.save(any(InvoicePaymentAllocation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.sumAllocatedByInvoiceId(900L)).thenReturn(new BigDecimal("50.00"));

        Invoice invoice = service.createDepositInvoiceForPaymentIntent(paymentIntent);

        assertThat(invoice.getCustomerName()).isEqualTo("John Doe");
        assertThat(invoice.getCustomerEmail()).isEqualTo("john@example.com");
        assertThat(invoice.getCustomerPhone()).isEqualTo("+38591111222");
    }

    @Test
    void findPreferredByRequestIdPrefersBusinessInvoiceOverDepositForDisplay() {
        Invoice deposit = Invoice.builder()
                .id(41L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .build();
        Invoice invoice = Invoice.builder()
                .id(43L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .build();
        Invoice storno = Invoice.builder()
                .id(42L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT_STORNO)
                .build();

        when(invoiceRepo.findByReservationRequestIdOrderByCreatedAtDescIdDesc(100L))
                .thenReturn(List.of(invoice, storno, deposit));

        assertThat(service.findPreferredByRequestId(100L)).contains(invoice);
        assertThat(service.findCancellationSourceInvoiceByRequestId(100L)).contains(deposit);
    }

    @Test
    void createStornoInvoiceUsesNegativeQuantityAndPositiveUnitPrice() {
        Invoice source = Invoice.builder()
                .id(500L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .currency("EUR")
                .customerName("John Doe")
                .subtotalNet(new BigDecimal("24.00"))
                .discountTotal(BigDecimal.ZERO)
                .tax1Total(new BigDecimal("6.00"))
                .tax2Total(BigDecimal.ZERO)
                .totalGross(new BigDecimal("30.00"))
                .build();
        InvoiceItem sourceItem = InvoiceItem.builder()
                .invoice(source)
                .lineNo(1)
                .reservationId(10L)
                .productId(20L)
                .productName("Deposit")
                .quantity(2)
                .unitPriceGross(new BigDecimal("15.00"))
                .discountPercent(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .priceWithoutTax(new BigDecimal("24.00"))
                .tax1Percent(new BigDecimal("25.0000"))
                .tax2Percent(BigDecimal.ZERO)
                .tax1Amount(new BigDecimal("6.00"))
                .tax2Amount(BigDecimal.ZERO)
                .nettPrice(new BigDecimal("24.00"))
                .grossAmount(new BigDecimal("30.00"))
                .build();
        InvoiceSequence sequence = InvoiceSequence.builder()
                .tenantId(1L)
                .invoiceType("DEPOSIT_STORNO")
                .invoiceYear(LocalDate.now().getYear())
                .lastNumber(0)
                .build();

        when(invoiceRepo.findById(500L)).thenReturn(Optional.of(source));
        when(sequenceRepo.findForUpdate(1L, "DEPOSIT_STORNO", LocalDate.now().getYear()))
                .thenReturn(Optional.of(sequence));
        when(sequenceRepo.save(any(InvoiceSequence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(501L);
            }
            return invoice;
        });
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(500L)).thenReturn(List.of(sourceItem));
        when(invoiceItemRepo.save(any(InvoiceItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(500L)).thenReturn(List.of());
        when(allocationRepo.sumAllocatedByInvoiceId(501L)).thenReturn(BigDecimal.ZERO);

        service.createStornoInvoice(500L);

        ArgumentCaptor<InvoiceItem> itemCaptor = ArgumentCaptor.forClass(InvoiceItem.class);
        verify(invoiceItemRepo).save(itemCaptor.capture());
        InvoiceItem stornoItem = itemCaptor.getValue();
        assertThat(stornoItem.getQuantity()).isEqualTo(-2);
        assertThat(stornoItem.getUnitPriceGross()).isEqualByComparingTo("15.00");
        assertThat(stornoItem.getGrossAmount()).isEqualByComparingTo("-30.00");
    }

    @Test
    void createCreditNoteInvoiceUsesNegativeQuantityAndPositiveUnitPrice() {
        Invoice source = Invoice.builder()
                .id(600L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .currency("EUR")
                .customerName("John Doe")
                .subtotalNet(new BigDecimal("24.00"))
                .discountTotal(BigDecimal.ZERO)
                .tax1Total(new BigDecimal("6.00"))
                .tax2Total(BigDecimal.ZERO)
                .totalGross(new BigDecimal("30.00"))
                .build();
        InvoiceItem sourceItem = InvoiceItem.builder()
                .invoice(source)
                .lineNo(1)
                .reservationId(10L)
                .productId(20L)
                .productName("Room")
                .quantity(2)
                .unitPriceGross(new BigDecimal("15.00"))
                .discountPercent(BigDecimal.ZERO)
                .discountAmount(BigDecimal.ZERO)
                .priceWithoutTax(new BigDecimal("24.00"))
                .tax1Percent(new BigDecimal("25.0000"))
                .tax2Percent(BigDecimal.ZERO)
                .tax1Amount(new BigDecimal("6.00"))
                .tax2Amount(BigDecimal.ZERO)
                .nettPrice(new BigDecimal("24.00"))
                .grossAmount(new BigDecimal("30.00"))
                .build();
        InvoiceSequence sequence = InvoiceSequence.builder()
                .tenantId(1L)
                .invoiceType("CREDIT_NOTE")
                .invoiceYear(LocalDate.now().getYear())
                .lastNumber(0)
                .build();

        when(invoiceRepo.findById(600L)).thenReturn(Optional.of(source));
        when(sequenceRepo.findForUpdate(1L, "CREDIT_NOTE", LocalDate.now().getYear()))
                .thenReturn(Optional.of(sequence));
        when(sequenceRepo.save(any(InvoiceSequence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(601L);
            }
            return invoice;
        });
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(600L)).thenReturn(List.of(sourceItem));
        when(invoiceItemRepo.save(any(InvoiceItem.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.sumAllocatedByInvoiceId(601L)).thenReturn(BigDecimal.ZERO);

        service.createCreditNoteInvoice(600L);

        ArgumentCaptor<InvoiceItem> itemCaptor = ArgumentCaptor.forClass(InvoiceItem.class);
        verify(invoiceItemRepo).save(itemCaptor.capture());
        InvoiceItem creditNoteItem = itemCaptor.getValue();
        assertThat(creditNoteItem.getQuantity()).isEqualTo(-2);
        assertThat(creditNoteItem.getUnitPriceGross()).isEqualByComparingTo("15.00");
        assertThat(creditNoteItem.getGrossAmount()).isEqualByComparingTo("-30.00");
    }

    @Test
    void allocateNegativeAmountOnCreditStyleInvoiceReleasesChargeCapacity() {
        Invoice invoice = Invoice.builder()
                .id(901L)
                .tenantId(1L)
                .totalGross(new BigDecimal("-50.00"))
                .build();
        PaymentTransaction charge = PaymentTransaction.builder()
                .id(700L)
                .tenantId(1L)
                .transactionType("CHARGE")
                .status("POSTED")
                .paymentType("CARD")
                .currency("EUR")
                .amount(new BigDecimal("100.00"))
                .build();

        when(invoiceRepo.findById(901L)).thenReturn(Optional.of(invoice));
        when(paymentTransactionService.requireById(700L)).thenReturn(charge);
        when(allocationRepo.sumAllocatedByPaymentTransactionId(700L)).thenReturn(new BigDecimal("100.00"));
        when(allocationRepo.findByInvoiceIdAndPaymentTransactionId(901L, 700L)).thenReturn(Optional.empty());
        when(allocationRepo.save(any(InvoicePaymentAllocation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.sumAllocatedByInvoiceId(901L)).thenReturn(new BigDecimal("-50.00"));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InvoicePaymentAllocation allocation = service.allocatePaymentToInvoice(901L, 700L, new BigDecimal("-50.00"), "refund_release");

        assertThat(allocation.getAllocatedAmount()).isEqualTo(new BigDecimal("-50.00"));
        assertThat(allocation.getAllocationType()).isEqualTo("REFUND_RELEASE");
    }

    @Test
    void allocateRejectsRefundTransactionAsInvoiceSource() {
        Invoice invoice = Invoice.builder()
                .id(902L)
                .tenantId(1L)
                .totalGross(new BigDecimal("-50.00"))
                .build();
        PaymentTransaction refund = PaymentTransaction.builder()
                .id(701L)
                .tenantId(1L)
                .transactionType("REFUND")
                .status("POSTED")
                .paymentType("CARD")
                .currency("EUR")
                .amount(new BigDecimal("-50.00"))
                .build();

        when(invoiceRepo.findById(902L)).thenReturn(Optional.of(invoice));
        when(paymentTransactionService.requireById(701L)).thenReturn(refund);

        assertThatThrownBy(() -> service.allocatePaymentToInvoice(902L, 701L, new BigDecimal("-50.00"), "refund_release"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CHARGE");
    }

    @Test
    void createManualRoomChargeDraftStoresOperaTargetFields() {
        Long tenantId = 1L;
        Product product = Product.builder()
                .id(77L)
                .tenantId(tenantId)
                .name("Room Charge")
                .tax1Percent(new BigDecimal("25"))
                .tax2Percent(BigDecimal.ZERO)
                .build();
        InvoiceSequence sequence = InvoiceSequence.builder()
                .tenantId(tenantId)
                .invoiceType("ROOM_CHARGE")
                .invoiceYear(LocalDate.now().getYear())
                .lastNumber(0)
                .build();
        AppUser onlineSystemUser = AppUser.builder()
                .id(12L)
                .tenantId(tenantId)
                .username("online-system-tenant-1")
                .build();

        InvoiceCreateRequest request = new InvoiceCreateRequest();
        request.setTenantId(tenantId);
        request.setInvoiceType(InvoiceType.ROOM_CHARGE);
        request.setCurrency("eur");
        request.setOperaReservationId(460983L);
        request.setOperaHotelCode("dh");

        InvoiceCreateItemRequest item = new InvoiceCreateItemRequest();
        item.setProductId(product.getId());
        item.setQuantity(1);
        item.setUnitPriceGross(new BigDecimal("170.00"));
        request.setItems(List.of(item));

        when(productRepo.findByIdAndTenantId(product.getId(), tenantId)).thenReturn(Optional.of(product));
        when(sequenceRepo.findForUpdate(tenantId, "ROOM_CHARGE", LocalDate.now().getYear()))
                .thenReturn(Optional.of(sequence));
        when(sequenceRepo.save(any(InvoiceSequence.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(appUserRepo.findByTenantIdAndUsername(tenantId, "online-system-tenant-1"))
                .thenReturn(Optional.of(onlineSystemUser));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> {
            Invoice invoice = invocation.getArgument(0);
            if (invoice.getId() == null) {
                invoice.setId(901L);
            }
            return invoice;
        });
        when(invoiceItemRepo.save(any(InvoiceItem.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Invoice invoice = service.createManualDraft(request);

        assertThat(invoice.getInvoiceType()).isEqualTo(InvoiceType.ROOM_CHARGE);
        assertThat(invoice.getOperaReservationId()).isEqualTo(460983L);
        assertThat(invoice.getOperaHotelCode()).isEqualTo("DH");
        assertThat(invoice.getOperaPostingStatus()).isEqualTo(OperaPostingStatus.NOT_POSTED);
    }
}
