package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import com.stackwizard.booking_api.model.OperaPostingStatus;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.service.PaymentTransactionService;
import com.stackwizard.booking_api.service.fiscal.OperaFiscalMappingService;
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
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OperaInvoicePostingServiceTest {

    @Mock
    private InvoiceRepository invoiceRepo;
    @Mock
    private InvoiceItemRepository invoiceItemRepo;
    @Mock
    private InvoicePaymentAllocationRepository allocationRepo;
    @Mock
    private ProductRepository productRepo;
    @Mock
    private PaymentTransactionService paymentTransactionService;
    @Mock
    private OperaFiscalMappingService operaFiscalMappingService;
    @Mock
    private OperaPostingConfigurationService configurationService;
    @Mock
    private OperaTenantConfigResolver tenantConfigResolver;
    @Mock
    private OperaPostingClient operaPostingClient;

    private OperaInvoicePostingService service;

    @BeforeEach
    void setUp() {
        service = new OperaInvoicePostingService(
                invoiceRepo,
                invoiceItemRepo,
                allocationRepo,
                productRepo,
                paymentTransactionService,
                operaFiscalMappingService,
                configurationService,
                tenantConfigResolver,
                operaPostingClient
        );
    }

    @Test
    void previewRoomChargeUsesInvoiceTargetAndCardSpecificPaymentMapping() {
        Invoice invoice = Invoice.builder()
                .id(10L)
                .tenantId(1L)
                .invoiceType(InvoiceType.ROOM_CHARGE)
                .invoiceNumber("ROOM-2026-00001")
                .fiscalFolioNo("FISC-2026-00001")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("PAID")
                .currency("EUR")
                .totalGross(new BigDecimal("170.00"))
                .operaReservationId(460983L)
                .operaHotelCode("DH")
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();
        InvoiceItem item = InvoiceItem.builder()
                .id(100L)
                .invoice(invoice)
                .lineNo(1)
                .productId(77L)
                .productName("Room Charge")
                .quantity(1)
                .unitPriceGross(new BigDecimal("170.00"))
                .grossAmount(new BigDecimal("170.00"))
                .build();
        InvoicePaymentAllocation allocation = InvoicePaymentAllocation.builder()
                .id(200L)
                .invoice(invoice)
                .paymentTransactionId(501L)
                .allocatedAmount(new BigDecimal("170.00"))
                .build();
        Product product = Product.builder()
                .id(77L)
                .tenantId(1L)
                .productType("ROOM")
                .build();
        OperaFiscalChargeMapping chargeMapping = OperaFiscalChargeMapping.builder()
                .id(301L)
                .tenantId(1L)
                .trxCode("10010")
                .build();
        PaymentTransaction paymentTransaction = PaymentTransaction.builder()
                .id(501L)
                .tenantId(1L)
                .paymentType("CARD")
                .cardType("VISA")
                .currency("EUR")
                .amount(new BigDecimal("170.00"))
                .status("POSTED")
                .build();
        OperaFiscalPaymentMapping paymentMapping = OperaFiscalPaymentMapping.builder()
                .id(401L)
                .tenantId(1L)
                .paymentType("CARD")
                .cardType("VISA")
                .trxCode("90010")
                .paymentMethodCode("VA")
                .active(Boolean.TRUE)
                .build();
        OperaHotel hotel = OperaHotel.builder()
                .id(601L)
                .tenantId(1L)
                .hotelCode("DH")
                .defaultCashierId(19L)
                .defaultFolioWindowNo(1)
                .active(Boolean.TRUE)
                .build();

        when(invoiceRepo.findById(10L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(10L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(10L)).thenReturn(List.of(allocation));
        when(productRepo.findAllById(anyIterable())).thenReturn(List.of(product));
        when(configurationService.requireActiveHotel(1L, "DH")).thenReturn(hotel);
        when(operaFiscalMappingService.resolveChargeMapping(1L, 77L, "ROOM")).thenReturn(Optional.of(chargeMapping));
        when(paymentTransactionService.requireById(501L)).thenReturn(paymentTransaction);
        when(operaFiscalMappingService.resolvePaymentMapping(1L, "CARD", "VISA")).thenReturn(Optional.of(paymentMapping));

        OperaInvoicePostRequest request = new OperaInvoicePostRequest();
        request.setPostingReference("IGNORED-REFERENCE");
        request.setPostingRemark("IGNORED-REMARK");

        OperaInvoicePostingPreview preview = service.previewInvoice(10L, request);

        assertThat(preview.hotelCode()).isEqualTo("DH");
        assertThat(preview.reservationId()).isEqualTo(460983L);
        assertThat(preview.cashierId()).isEqualTo(19L);
        assertThat(preview.folioWindowNo()).isEqualTo(1);
        JsonNode payload = preview.payload();
        assertThat(payload.path("charges").get(0).path("transactionCode").asText()).isEqualTo("10010");
        assertThat(payload.path("charges").get(0).path("postingReference").asText()).isEqualTo("FISC-2026-00001");
        assertThat(payload.path("charges").get(0).path("postingRemark").asText()).isEqualTo("Room Charge");
        assertThat(payload.path("payments").get(0).path("paymentMethod").path("paymentMethod").asText()).isEqualTo("VA");
        assertThat(payload.path("payments").get(0).path("postingReference").asText()).isEqualTo("FISC-2026-00001");
        assertThat(payload.path("payments").get(0).path("postingRemark").asText()).isEqualTo("FISC-2026-00001");
        verify(configurationService, never()).resolveRouting(any(), any(), any());
    }

    @Test
    void postInvoiceFallsBackToInvoiceTypeRoutingAndStoresPostingState() {
        Invoice invoice = Invoice.builder()
                .id(11L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber("DEPOSIT-2026-00001")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .currency("EUR")
                .totalGross(new BigDecimal("50.00"))
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();
        InvoiceItem item = InvoiceItem.builder()
                .id(110L)
                .invoice(invoice)
                .lineNo(1)
                .productId(91L)
                .productName("Deposit")
                .quantity(1)
                .unitPriceGross(new BigDecimal("50.00"))
                .grossAmount(new BigDecimal("50.00"))
                .build();
        Product product = Product.builder()
                .id(91L)
                .tenantId(1L)
                .productType("DEPOSIT")
                .build();
        OperaFiscalChargeMapping chargeMapping = OperaFiscalChargeMapping.builder()
                .id(302L)
                .tenantId(1L)
                .trxCode("20010")
                .build();
        OperaInvoiceTypeRouting routing = OperaInvoiceTypeRouting.builder()
                .id(701L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .hotelCode("DH")
                .reservationId(99001L)
                .active(Boolean.TRUE)
                .build();
        OperaHotel hotel = OperaHotel.builder()
                .id(601L)
                .tenantId(1L)
                .hotelCode("DH")
                .defaultCashierId(19L)
                .defaultFolioWindowNo(1)
                .active(Boolean.TRUE)
                .build();
        JsonNode response = new ObjectMapper().createObjectNode().put("status", "ok");

        when(invoiceRepo.findById(11L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(11L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(11L)).thenReturn(List.of());
        when(productRepo.findAllById(anyIterable())).thenReturn(List.of(product));
        when(tenantConfigResolver.findDefaultHotelCode(1L)).thenReturn(Optional.empty());
        when(configurationService.resolveRouting(1L, InvoiceType.DEPOSIT, null)).thenReturn(routing);
        when(configurationService.requireActiveHotel(1L, "DH")).thenReturn(hotel);
        when(operaFiscalMappingService.resolveChargeMapping(1L, 91L, "DEPOSIT")).thenReturn(Optional.of(chargeMapping));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operaPostingClient.postChargesAndPayments(any(), eq("DH"), eq(99001L), any())).thenReturn(response);

        OperaInvoicePostRequest request = new OperaInvoicePostRequest();
        request.setBaseUrl("https://opera.example");
        request.setAppKey("app-key");
        request.setAccessToken("token");

        OperaInvoicePostingResult result = service.postInvoice(11L, request);

        assertThat(result.hotelCode()).isEqualTo("DH");
        assertThat(result.reservationId()).isEqualTo(99001L);
        assertThat(result.invoice().getOperaPostingStatus()).isEqualTo(OperaPostingStatus.POSTED);
        assertThat(result.invoice().getOperaHotelCode()).isEqualTo("DH");
        assertThat(result.invoice().getOperaReservationId()).isEqualTo(99001L);
        assertThat(result.response().path("status").asText()).isEqualTo("ok");
        verify(configurationService).resolveRouting(1L, InvoiceType.DEPOSIT, null);
    }

    @Test
    void previewNonRoomChargeUsesDefaultHotelFromConfigForRoutingSelection() {
        Invoice invoice = Invoice.builder()
                .id(12L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber("DEPOSIT-2026-00002")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .currency("EUR")
                .totalGross(new BigDecimal("50.00"))
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();
        InvoiceItem item = InvoiceItem.builder()
                .id(111L)
                .invoice(invoice)
                .lineNo(1)
                .productId(91L)
                .productName("Deposit")
                .quantity(1)
                .unitPriceGross(new BigDecimal("50.00"))
                .grossAmount(new BigDecimal("50.00"))
                .build();
        Product product = Product.builder()
                .id(91L)
                .tenantId(1L)
                .productType("DEPOSIT")
                .build();
        OperaFiscalChargeMapping chargeMapping = OperaFiscalChargeMapping.builder()
                .id(303L)
                .tenantId(1L)
                .trxCode("20010")
                .build();
        OperaInvoiceTypeRouting routing = OperaInvoiceTypeRouting.builder()
                .id(702L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .hotelCode("DH")
                .reservationId(99002L)
                .active(Boolean.TRUE)
                .build();
        OperaHotel hotel = OperaHotel.builder()
                .id(602L)
                .tenantId(1L)
                .hotelCode("DH")
                .defaultCashierId(19L)
                .defaultFolioWindowNo(1)
                .active(Boolean.TRUE)
                .build();

        when(invoiceRepo.findById(12L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(12L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(12L)).thenReturn(List.of());
        when(productRepo.findAllById(anyIterable())).thenReturn(List.of(product));
        when(tenantConfigResolver.findDefaultHotelCode(1L)).thenReturn(Optional.of("DH"));
        when(configurationService.resolveRouting(1L, InvoiceType.DEPOSIT, "DH")).thenReturn(routing);
        when(configurationService.requireActiveHotel(1L, "DH")).thenReturn(hotel);
        when(operaFiscalMappingService.resolveChargeMapping(1L, 91L, "DEPOSIT")).thenReturn(Optional.of(chargeMapping));

        OperaInvoicePostingPreview preview = service.previewInvoice(12L, new OperaInvoicePostRequest());

        assertThat(preview.hotelCode()).isEqualTo("DH");
        assertThat(preview.reservationId()).isEqualTo(99002L);
        verify(configurationService).resolveRouting(1L, InvoiceType.DEPOSIT, "DH");
    }

    @Test
    void postInvoiceIgnoresConfiguredStaticAccessToken() {
        Invoice invoice = Invoice.builder()
                .id(13L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber("DEPOSIT-2026-00003")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .currency("EUR")
                .totalGross(new BigDecimal("50.00"))
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();
        InvoiceItem item = InvoiceItem.builder()
                .id(112L)
                .invoice(invoice)
                .lineNo(1)
                .productId(91L)
                .productName("Deposit")
                .quantity(1)
                .unitPriceGross(new BigDecimal("50.00"))
                .grossAmount(new BigDecimal("50.00"))
                .build();
        Product product = Product.builder()
                .id(91L)
                .tenantId(1L)
                .productType("DEPOSIT")
                .build();
        OperaFiscalChargeMapping chargeMapping = OperaFiscalChargeMapping.builder()
                .id(304L)
                .tenantId(1L)
                .trxCode("20010")
                .build();
        OperaInvoiceTypeRouting routing = OperaInvoiceTypeRouting.builder()
                .id(703L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .hotelCode("DH")
                .reservationId(99003L)
                .active(Boolean.TRUE)
                .build();
        OperaHotel hotel = OperaHotel.builder()
                .id(603L)
                .tenantId(1L)
                .hotelCode("DH")
                .defaultCashierId(19L)
                .defaultFolioWindowNo(1)
                .active(Boolean.TRUE)
                .build();
        OperaTenantConfigResolver.OperaResolvedConfig tenantConfig = new OperaTenantConfigResolver.OperaResolvedConfig(
                "https://opera.example",
                "/oauth/v1/tokens",
                "app-key",
                "client-id",
                "client-secret",
                "MIKOSE",
                null
        );
        JsonNode response = new ObjectMapper().createObjectNode().put("status", "ok");

        when(invoiceRepo.findById(13L)).thenReturn(Optional.of(invoice));
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(13L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(13L)).thenReturn(List.of());
        when(productRepo.findAllById(anyIterable())).thenReturn(List.of(product));
        when(tenantConfigResolver.findDefaultHotelCode(1L)).thenReturn(Optional.of("DH"));
        when(configurationService.resolveRouting(1L, InvoiceType.DEPOSIT, "DH")).thenReturn(routing);
        when(configurationService.requireActiveHotel(1L, "DH")).thenReturn(hotel);
        when(operaFiscalMappingService.resolveChargeMapping(1L, 91L, "DEPOSIT")).thenReturn(Optional.of(chargeMapping));
        when(tenantConfigResolver.resolve(1L)).thenReturn(tenantConfig);
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        doAnswer(invocation -> {
            OperaTenantConfigResolver.OperaResolvedConfig config = invocation.getArgument(0);
            assertThat(config.accessToken()).isNull();
            assertThat(config.clientId()).isEqualTo("client-id");
            return response;
        }).when(operaPostingClient).postChargesAndPayments(any(), eq("DH"), eq(99003L), any());

        OperaInvoicePostingResult result = service.postInvoice(13L, new OperaInvoicePostRequest());

        assertThat(result.response().path("status").asText()).isEqualTo("ok");
    }

    @Test
    void tryAutoPostInvoiceSkipsWhenOperaConfigIsMissing() {
        Invoice invoice = Invoice.builder()
                .id(14L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber("DEPOSIT-2026-00004")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .currency("EUR")
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();

        when(invoiceRepo.findById(14L)).thenReturn(Optional.of(invoice));
        when(tenantConfigResolver.resolve(1L)).thenThrow(new IllegalStateException("Opera config is missing"));

        Invoice result = service.tryAutoPostInvoice(14L);

        assertThat(result).isSameAs(invoice);
        verify(invoiceItemRepo, never()).findByInvoiceIdOrderByLineNoAsc(any());
        verify(operaPostingClient, never()).postChargesAndPayments(any(), any(), any(), any());
    }

    @Test
    void tryAutoPostInvoiceKeepsFailedOperaStatusWhenPostingThrows() {
        Invoice invoice = Invoice.builder()
                .id(15L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber("DEPOSIT-2026-00005")
                .invoiceDate(LocalDate.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .currency("EUR")
                .totalGross(new BigDecimal("50.00"))
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .build();
        InvoiceItem item = InvoiceItem.builder()
                .id(113L)
                .invoice(invoice)
                .lineNo(1)
                .productId(91L)
                .productName("Deposit")
                .quantity(1)
                .unitPriceGross(new BigDecimal("50.00"))
                .grossAmount(new BigDecimal("50.00"))
                .build();
        Product product = Product.builder()
                .id(91L)
                .tenantId(1L)
                .productType("DEPOSIT")
                .build();
        OperaFiscalChargeMapping chargeMapping = OperaFiscalChargeMapping.builder()
                .id(305L)
                .tenantId(1L)
                .trxCode("20010")
                .build();
        OperaInvoiceTypeRouting routing = OperaInvoiceTypeRouting.builder()
                .id(704L)
                .tenantId(1L)
                .invoiceType(InvoiceType.DEPOSIT)
                .hotelCode("DH")
                .reservationId(99004L)
                .active(Boolean.TRUE)
                .build();
        OperaHotel hotel = OperaHotel.builder()
                .id(604L)
                .tenantId(1L)
                .hotelCode("DH")
                .defaultCashierId(19L)
                .defaultFolioWindowNo(1)
                .active(Boolean.TRUE)
                .build();
        OperaTenantConfigResolver.OperaResolvedConfig tenantConfig = new OperaTenantConfigResolver.OperaResolvedConfig(
                "https://opera.example",
                "/oauth/v1/tokens",
                "app-key",
                "client-id",
                "client-secret",
                "MIKOSE",
                null
        );

        when(invoiceRepo.findById(15L)).thenReturn(Optional.of(invoice));
        when(tenantConfigResolver.resolve(1L)).thenReturn(tenantConfig);
        when(invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(15L)).thenReturn(List.of(item));
        when(allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(15L)).thenReturn(List.of());
        when(productRepo.findAllById(anyIterable())).thenReturn(List.of(product));
        when(tenantConfigResolver.findDefaultHotelCode(1L)).thenReturn(Optional.of("DH"));
        when(configurationService.resolveRouting(1L, InvoiceType.DEPOSIT, "DH")).thenReturn(routing);
        when(configurationService.requireActiveHotel(1L, "DH")).thenReturn(hotel);
        when(operaFiscalMappingService.resolveChargeMapping(1L, 91L, "DEPOSIT")).thenReturn(Optional.of(chargeMapping));
        when(invoiceRepo.save(any(Invoice.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(operaPostingClient.postChargesAndPayments(any(), eq("DH"), eq(99004L), any()))
                .thenThrow(new IllegalStateException("OHIP request failed"));

        Invoice result = service.tryAutoPostInvoice(15L);

        assertThat(result.getOperaPostingStatus()).isEqualTo(OperaPostingStatus.FAILED);
        assertThat(result.getOperaErrorMessage()).isEqualTo("OHIP request failed");
    }
}
