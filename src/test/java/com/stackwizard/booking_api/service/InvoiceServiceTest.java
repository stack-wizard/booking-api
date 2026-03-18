package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceSequence;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
}
