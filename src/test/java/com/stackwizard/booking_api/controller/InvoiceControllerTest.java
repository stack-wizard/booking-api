package com.stackwizard.booking_api.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.InvoiceIssueRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.service.InvoicePdfService;
import com.stackwizard.booking_api.service.InvoiceService;
import com.stackwizard.booking_api.service.fiscal.InvoiceFiscalizationService;
import com.stackwizard.booking_api.service.opera.OperaInvoicePostingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InvoiceControllerTest {

    @Mock
    private InvoiceService invoiceService;
    @Mock
    private InvoicePdfService invoicePdfService;
    @Mock
    private InvoiceFiscalizationService invoiceFiscalizationService;
    @Mock
    private OperaInvoicePostingService operaInvoicePostingService;

    @Test
    void previewOperaPostingReturnsOnlyConstructedOperaPayload() {
        InvoiceController controller = new InvoiceController(
                invoiceService,
                invoicePdfService,
                invoiceFiscalizationService,
                operaInvoicePostingService
        );

        ObjectNode payload = JsonNodeFactory.instance.objectNode();
        payload.put("cashierId", 19);
        payload.set("charges", JsonNodeFactory.instance.arrayNode()
                .add(JsonNodeFactory.instance.objectNode().put("transactionCode", "10010")));
        payload.set("payments", JsonNodeFactory.instance.arrayNode());

        OperaInvoicePostRequest request = new OperaInvoicePostRequest();
        when(operaInvoicePostingService.previewPayload(32L, request)).thenReturn(payload);

        ResponseEntity<String> response = controller.previewOperaPosting(32L, request);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isEqualTo(payload.toPrettyString());
        assertThat(response.getBody()).contains("\"cashierId\" : 19");
        verify(operaInvoicePostingService).previewPayload(32L, request);
    }

    @Test
    void issueInvoiceReturnsIssuedInvoiceEvenWhenFiscalizationFails() {
        InvoiceController controller = new InvoiceController(
                invoiceService,
                invoicePdfService,
                invoiceFiscalizationService,
                operaInvoicePostingService
        );

        Invoice issued = Invoice.builder()
                .id(44L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .invoiceNumber("INV-1")
                .invoiceDate(LocalDate.of(2026, 4, 1))
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .currency("EUR")
                .subtotalNet(new BigDecimal("80.00"))
                .discountTotal(BigDecimal.ZERO)
                .tax1Total(new BigDecimal("20.00"))
                .tax2Total(BigDecimal.ZERO)
                .totalGross(new BigDecimal("100.00"))
                .build();
        Invoice failedFiscalization = Invoice.builder()
                .id(44L)
                .tenantId(1L)
                .invoiceType(InvoiceType.INVOICE)
                .invoiceNumber("INV-1")
                .invoiceDate(LocalDate.of(2026, 4, 1))
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.FAILED)
                .currency("EUR")
                .subtotalNet(new BigDecimal("80.00"))
                .discountTotal(BigDecimal.ZERO)
                .tax1Total(new BigDecimal("20.00"))
                .tax2Total(BigDecimal.ZERO)
                .totalGross(new BigDecimal("100.00"))
                .build();

        when(invoiceService.issueInvoice(eq(44L), any(InvoiceIssueRequest.class))).thenReturn(issued);
        when(invoiceFiscalizationService.tryFiscalizeInvoice(any(), any())).thenReturn(failedFiscalization);
        lenient().when(invoiceService.findItems(44L)).thenReturn(java.util.List.of());
        lenient().when(invoiceService.findAllocations(44L)).thenReturn(java.util.List.of());

        ResponseEntity<Map<String, Object>> response = controller.issueInvoice(44L, new InvoiceIssueRequest());

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody()).isNotNull();
        Invoice returnedInvoice = (Invoice) response.getBody().get("invoice");
        assertThat(returnedInvoice.getFiscalizationStatus()).isEqualTo(InvoiceFiscalizationStatus.FAILED);
        assertThat(returnedInvoice.getStatus()).isEqualTo(InvoiceStatus.ISSUED);
    }
}
