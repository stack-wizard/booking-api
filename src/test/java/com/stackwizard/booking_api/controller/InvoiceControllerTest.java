package com.stackwizard.booking_api.controller;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.assertj.core.api.Assertions.assertThat;
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
}
