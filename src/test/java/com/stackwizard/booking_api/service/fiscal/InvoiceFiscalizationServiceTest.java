package com.stackwizard.booking_api.service.fiscal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.InvoiceFiscalizeRequest;
import com.stackwizard.booking_api.model.FiscalCashRegister;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.IssuedByMode;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.FiscalCashRegisterService;
import com.stackwizard.booking_api.service.InvoiceService;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import com.stackwizard.booking_api.service.opera.OperaInvoicePostingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceFiscalizationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private InvoiceRepository invoiceRepo;
    @Mock
    private InvoiceItemRepository invoiceItemRepo;
    @Mock
    private InvoicePaymentAllocationRepository allocationRepo;
    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private ProductRepository productRepo;
    @Mock
    private AppUserRepository appUserRepo;
    @Mock
    private PaymentTransactionService paymentTransactionService;
    @Mock
    private InvoiceService invoiceService;
    @Mock
    private FiscalCashRegisterService fiscalCashRegisterService;
    @Mock
    private TenantIntegrationConfigService tenantIntegrationConfigService;
    @Mock
    private OfisFiscalizationClient ofisFiscalizationClient;
    @Mock
    private OperaFiscalMappingService operaFiscalMappingService;
    @Mock
    private OperaInvoicePostingService operaInvoicePostingService;

    private InvoiceFiscalizationService service;

    @BeforeEach
    void setUp() {
        service = new InvoiceFiscalizationService(
                invoiceRepo,
                invoiceItemRepo,
                allocationRepo,
                requestRepo,
                productRepo,
                appUserRepo,
                paymentTransactionService,
                invoiceService,
                fiscalCashRegisterService,
                tenantIntegrationConfigService,
                ofisFiscalizationClient,
                operaFiscalMappingService,
                operaInvoicePostingService
        );
    }

    @Test
    void fiscalizeInvoiceTriggersAutomaticOperaPostingAfterSuccessfulFiscalization() {
        Invoice invoice = baseInvoice();
        ObjectNode ofisPayload = objectMapper.createObjectNode().put("document", "invoice");
        ObjectNode fiscalResponse = objectMapper.createObjectNode()
                .put("FiscalFolioNo", "FISC-2026-00001")
                .put("FiscalBillGenerationDateTime", OffsetDateTime.now().toString());

        when(invoiceRepo.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceService.issueInvoice(eq(5L), any())).thenReturn(invoice);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(5L)).thenReturn(List.of());
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(5L)).thenReturn(List.of());
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ofisFiscalizationClient.fiscalize(1L, ofisPayload)).thenReturn(fiscalResponse);
        when(operaInvoicePostingService.tryAutoPostInvoice(5L)).thenReturn(invoice);

        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setOfisPayload(ofisPayload);

        Invoice result = service.fiscalizeInvoice(5L, request);

        assertThat(result.getFiscalizationStatus()).isEqualTo(InvoiceFiscalizationStatus.FISCALIZED);
        assertThat(result.getFiscalFolioNo()).isEqualTo("FISC-2026-00001");
        verify(operaInvoicePostingService).tryAutoPostInvoice(5L);
    }

    @Test
    void fiscalizeInvoiceDoesNotAttemptAutomaticOperaPostingWhenFiscalizationFails() {
        Invoice invoice = baseInvoice();
        ObjectNode ofisPayload = objectMapper.createObjectNode().put("document", "invoice");

        when(invoiceRepo.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceService.issueInvoice(eq(5L), any())).thenReturn(invoice);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(5L)).thenReturn(List.of());
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(5L)).thenReturn(List.of());
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(ofisFiscalizationClient.fiscalize(1L, ofisPayload)).thenThrow(new IllegalStateException("OFIS failed"));

        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setOfisPayload(ofisPayload);

        assertThatThrownBy(() -> service.fiscalizeInvoice(5L, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("OFIS failed");
        assertThat(invoice.getFiscalizationStatus()).isEqualTo(InvoiceFiscalizationStatus.FAILED);
        verify(operaInvoicePostingService, never()).tryAutoPostInvoice(any());
    }

    @Test
    void buildFiscalPayloadPopulatesUserDefinedFieldsFromInvoiceSnapshots() {
        Invoice invoice = baseInvoice();
        invoice.setIssuedAt(OffsetDateTime.parse("2026-04-08T09:37:28Z"));
        invoice.setInvoiceDate(LocalDate.of(2026, 4, 8));
        invoice.setBusinessPremiseCodeSnapshot("1830");
        invoice.setCashRegisterCodeSnapshot("1");

        when(invoiceRepo.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceService.issueInvoice(eq(5L), any())).thenReturn(invoice);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(5L)).thenReturn(List.of());
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(5L)).thenReturn(List.of());
        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "FISCALIZATION", "OFIS"))
                .thenReturn(Optional.empty());
        when(fiscalCashRegisterService.requireByIdAndTenantId(22L, 1L)).thenReturn(FiscalCashRegister.builder()
                .id(22L)
                .tenantId(1L)
                .businessPremiseId(11L)
                .code("1")
                .terminalId("TERM-1")
                .active(Boolean.TRUE)
                .build());

        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setHotelCode("HOTEL");
        request.setPropertyTaxNumber("HR123");
        request.setConfirmationNo("CONF-412");

        var payload = service.buildFiscalPayload(5L, request);

        assertThat(payload.at("/UserDefinedFields/CharacterUDFs/0/UDF/0/Name").asText()).isEqualTo("FLIP_PARTNER_TAX1");
        assertThat(payload.at("/UserDefinedFields/CharacterUDFs/0/UDF/0/Value").asText()).isEqualTo("1830");
        assertThat(payload.at("/UserDefinedFields/CharacterUDFs/0/UDF/1/Name").asText()).isEqualTo("FLIP_PARTNER_TAX2");
        assertThat(payload.at("/UserDefinedFields/CharacterUDFs/0/UDF/1/Value").asText()).isEqualTo("1");
        assertThat(payload.at("/ReservationInfo/ConfirmationNo").asText()).isEqualTo("CONF-412+INV-2026-00001");
        assertThat(payload.at("/DocumentInfo/BusinessDateTime").asText()).isEqualTo("2026-04-08T11:37:28");
        verifyNoInteractions(operaFiscalMappingService);
    }

    @Test
    void buildFiscalPayloadKeepsNegativeTotalsForCreditNote() {
        Invoice invoice = baseInvoice();
        invoice.setInvoiceType(InvoiceType.CREDIT_NOTE);
        invoice.setInvoiceNumber("CREDIT_NOTE-2026-00001");

        InvoiceItem item = InvoiceItem.builder()
                .id(299L)
                .lineNo(1)
                .productName("DEPOSIT")
                .quantity(1)
                .priceWithoutTax(new BigDecimal("-40.00"))
                .tax1Percent(new BigDecimal("25.00"))
                .tax2Percent(BigDecimal.ZERO)
                .tax1Amount(new BigDecimal("-10.00"))
                .tax2Amount(BigDecimal.ZERO)
                .nettPrice(new BigDecimal("-40.00"))
                .grossAmount(new BigDecimal("-50.00"))
                .build();

        when(invoiceRepo.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceService.issueInvoice(eq(5L), any())).thenReturn(invoice);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(5L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(5L)).thenReturn(List.of());
        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "FISCALIZATION", "OFIS"))
                .thenReturn(Optional.empty());
        when(fiscalCashRegisterService.requireByIdAndTenantId(22L, 1L)).thenReturn(FiscalCashRegister.builder()
                .id(22L)
                .tenantId(1L)
                .businessPremiseId(11L)
                .code("1")
                .terminalId("TERM-1")
                .active(Boolean.TRUE)
                .build());
        when(operaFiscalMappingService.resolveChargeMapping(eq(1L), any(), any())).thenReturn(Optional.empty());
        when(operaFiscalMappingService.resolveTaxMapping(eq(1L), any())).thenReturn(Optional.empty());

        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setHotelCode("SUNHBC");
        request.setPropertyTaxNumber("29834131149");

        var payload = service.buildFiscalPayload(5L, request);

        assertThat(payload.at("/FolioInfo/TotalInfo/NetAmount").decimalValue()).isEqualByComparingTo("-40.00");
        assertThat(payload.at("/FolioInfo/TotalInfo/GrossAmount").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(payload.at("/FolioInfo/TotalInfo/Taxes/Tax/0/Value").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(payload.at("/FolioInfo/TotalInfo/Taxes/Tax/0/NetAmount").decimalValue()).isEqualByComparingTo("-40.00");
        assertThat(payload.at("/FolioInfo/RevenueBucketInfo/0/BucketCodeTotalGross").decimalValue())
                .isEqualByComparingTo("-50.00");
        assertThat(payload.at("/DocumentInfo/Command").asText()).isEqualTo("INVOICE");
        assertThat(payload.at("/DocumentInfo/DocumentType").asText()).isEqualTo("INVOICE");
    }

    @Test
    void buildFiscalPayloadKeepsNegativePostingAmountsForCreditNotePaymentAllocation() {
        Invoice invoice = baseInvoice();
        invoice.setInvoiceType(InvoiceType.CREDIT_NOTE);
        invoice.setInvoiceNumber("CREDIT_NOTE-2026-00001");

        InvoiceItem item = InvoiceItem.builder()
                .id(299L)
                .lineNo(1)
                .productName("DEPOSIT")
                .quantity(1)
                .priceWithoutTax(new BigDecimal("-40.00"))
                .tax1Percent(new BigDecimal("25.00"))
                .tax2Percent(BigDecimal.ZERO)
                .tax1Amount(new BigDecimal("-10.00"))
                .tax2Amount(BigDecimal.ZERO)
                .nettPrice(new BigDecimal("-40.00"))
                .grossAmount(new BigDecimal("-50.00"))
                .build();
        InvoicePaymentAllocation allocation = InvoicePaymentAllocation.builder()
                .paymentTransactionId(61L)
                .allocatedAmount(new BigDecimal("-50.00"))
                .allocationType("REFUND_RELEASE")
                .build();
        PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                .id(61L)
                .tenantId(1L)
                .transactionType("REFUND")
                .paymentType("CARD")
                .status("COMPLETED")
                .currency("EUR")
                .amount(new BigDecimal("-50.00"))
                .build();

        when(invoiceRepo.findById(5L)).thenReturn(Optional.of(invoice));
        when(invoiceService.issueInvoice(eq(5L), any())).thenReturn(invoice);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(5L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(5L)).thenReturn(List.of(allocation));
        when(paymentTransactionService.requireById(61L)).thenReturn(paymentTransaction);
        when(tenantIntegrationConfigService.findByTenantIdAndTypeAndProvider(1L, "FISCALIZATION", "OFIS"))
                .thenReturn(Optional.empty());
        when(fiscalCashRegisterService.requireByIdAndTenantId(22L, 1L)).thenReturn(FiscalCashRegister.builder()
                .id(22L)
                .tenantId(1L)
                .businessPremiseId(11L)
                .code("1")
                .terminalId("TERM-1")
                .active(Boolean.TRUE)
                .build());
        when(operaFiscalMappingService.resolveChargeMapping(eq(1L), any(), any())).thenReturn(Optional.empty());
        when(operaFiscalMappingService.resolveTaxMapping(eq(1L), any())).thenReturn(Optional.empty());
        when(operaFiscalMappingService.resolvePaymentMapping(eq(1L), eq("CARD"), any())).thenReturn(Optional.empty());

        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setHotelCode("SUNHBC");
        request.setPropertyTaxNumber("29834131149");

        var payload = service.buildFiscalPayload(5L, request);

        assertThat(payload.at("/FolioInfo/Postings/0/GrossAmount").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(payload.at("/FolioInfo/Postings/1/UnitPrice").decimalValue()).isEqualByComparingTo("-50.00");
        assertThat(payload.at("/FolioInfo/Postings/1/GrossAmount").decimalValue()).isEqualByComparingTo("-50.00");
    }

    private Invoice baseInvoice() {
        return Invoice.builder()
                .id(5L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .invoiceNumber("INV-2026-00001")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .issuedAt(OffsetDateTime.now())
                .issuedByMode(IssuedByMode.CASHIER)
                .businessPremiseId(11L)
                .cashRegisterId(22L)
                .businessPremiseCodeSnapshot("BP-11")
                .cashRegisterCodeSnapshot("CR-22")
                .paymentStatus("PAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .currency("EUR")
                .build();
    }
}
