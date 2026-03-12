package com.stackwizard.booking_api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.stackwizard.booking_api.dto.InvoiceFiscalizeRequest;
import com.stackwizard.booking_api.service.fiscal.InvoiceFiscalizationService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fiscalization-helper")
public class FiscalizationHelperController {
    private final InvoiceFiscalizationService invoiceFiscalizationService;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN);

    public FiscalizationHelperController(InvoiceFiscalizationService invoiceFiscalizationService) {
        this.invoiceFiscalizationService = invoiceFiscalizationService;
    }

    @PostMapping("/invoices/{invoiceId}/payload")
    public ResponseEntity<String> buildFiscalPayload(@PathVariable Long invoiceId,
                                                     @RequestBody(required = false) InvoiceFiscalizeRequest request) {
        JsonNode payload = invoiceFiscalizationService.buildFiscalPayload(
                invoiceId,
                request != null ? request : new InvoiceFiscalizeRequest()
        );
        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(payload));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize fiscal payload", ex);
        }
    }
}
