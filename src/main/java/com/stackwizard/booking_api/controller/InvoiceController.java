package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.service.InvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getById(@PathVariable Long id) {
        return invoiceService.findById(id)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-request/{requestId}")
    public ResponseEntity<Map<String, Object>> getByRequestId(@PathVariable Long requestId) {
        return invoiceService.findByReference("reservation_request", requestId)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getByReference(@RequestParam String referenceTable,
                                                              @RequestParam Long referenceId) {
        return invoiceService.findByReference(referenceTable, referenceId)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private ResponseEntity<Map<String, Object>> toResponse(Invoice invoice) {
        List<InvoiceItem> items = invoiceService.findItems(invoice.getId());
        return ResponseEntity.ok(Map.of(
                "invoice", invoice,
                "items", items
        ));
    }
}
