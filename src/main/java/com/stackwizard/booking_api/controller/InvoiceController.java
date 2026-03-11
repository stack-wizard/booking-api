package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.InvoiceSearchCriteria;
import com.stackwizard.booking_api.dto.InvoicePaymentAllocationRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.service.InvoiceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/invoices")
public class InvoiceController {
    private static final int MAX_PAGE_SIZE = 200;

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
        return invoiceService.findPreferredByRequestId(requestId)
                .map(this::toResponse)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/by-request/{requestId}/all")
    public ResponseEntity<List<Map<String, Object>>> getAllByRequestId(@PathVariable Long requestId) {
        List<Map<String, Object>> invoices = invoiceService.findByRequestId(requestId).stream()
                .map(this::toPayload)
                .toList();
        return ResponseEntity.ok(invoices);
    }

    @GetMapping
    public ResponseEntity<?> getByReference(InvoiceSearchCriteria criteria,
                                            @RequestParam(defaultValue = "0") int page,
                                            @RequestParam(defaultValue = "20") int size,
                                            @RequestParam(defaultValue = "createdAt") String sortBy,
                                            @RequestParam(defaultValue = "desc") String sortDir) {
        if (criteria.getReferenceTable() != null && criteria.getReferenceId() != null) {
            return invoiceService.findByReference(criteria.getReferenceTable(), criteria.getReferenceId())
                    .map(this::toResponse)
                    .orElseGet(() -> ResponseEntity.notFound().build());
        }

        if (criteria.getReferenceTable() != null || criteria.getReferenceId() != null) {
            throw new IllegalArgumentException("referenceTable and referenceId must both be provided together");
        }

        Pageable pageable = buildPageable(page, size, sortBy, sortDir);
        Page<Map<String, Object>> result = invoiceService.search(criteria, pageable)
                .map(this::toPayload);
        return ResponseEntity.ok(result);
    }

    @PostMapping("/{invoiceId}/allocations")
    public ResponseEntity<InvoicePaymentAllocation> allocatePayment(@PathVariable Long invoiceId,
                                                                    @RequestBody InvoicePaymentAllocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        InvoicePaymentAllocation allocation = invoiceService.allocatePaymentToInvoice(
                invoiceId, request.getPaymentTransactionId(), request.getAmount());
        return ResponseEntity.ok(allocation);
    }

    @PostMapping("/{invoiceId}/storno")
    public ResponseEntity<Map<String, Object>> createStorno(@PathVariable Long invoiceId) {
        Invoice storno = invoiceService.createStornoInvoice(invoiceId);
        return toResponse(storno);
    }

    @DeleteMapping("/{invoiceId}/allocations/{paymentTransactionId}")
    public ResponseEntity<Void> removeAllocation(@PathVariable Long invoiceId,
                                                 @PathVariable Long paymentTransactionId) {
        invoiceService.removePaymentAllocation(invoiceId, paymentTransactionId);
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<Map<String, Object>> toResponse(Invoice invoice) {
        return ResponseEntity.ok(toPayload(invoice));
    }

    private Map<String, Object> toPayload(Invoice invoice) {
        List<InvoiceItem> items = invoiceService.findItems(invoice.getId());
        List<InvoicePaymentAllocation> allocations = invoiceService.findAllocations(invoice.getId());
        return Map.of(
                "invoice", invoice,
                "items", items,
                "allocations", allocations
        );
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }
        String sortProperty = resolveSortProperty(sortBy);
        Sort.Direction direction = resolveDirection(sortDir);
        return PageRequest.of(page, size, Sort.by(direction, sortProperty));
    }

    private String resolveSortProperty(String sortBy) {
        String raw = sortBy == null ? "createdAt" : sortBy.trim();
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "id", "invoiceid" -> "id";
            case "invoicenumber", "invoice_number" -> "invoiceNumber";
            case "invoicetype", "invoice_type" -> "invoiceType";
            case "invoicedate", "invoice_date" -> "invoiceDate";
            case "status" -> "status";
            case "paymentstatus", "payment_status" -> "paymentStatus";
            case "fiscalizationstatus", "fiscalization_status" -> "fiscalizationStatus";
            case "customername", "customer_name" -> "customerName";
            case "currency" -> "currency";
            case "totalgross", "total_gross" -> "totalGross";
            case "reservationrequestid", "reservation_request_id", "reservationrequest", "reservation_reqst", "reservation_request" -> "reservationRequestId";
            case "createdat", "created_at" -> "createdAt";
            default -> throw new IllegalArgumentException("Unsupported sortBy value: " + sortBy);
        };
    }

    private Sort.Direction resolveDirection(String sortDir) {
        String normalized = sortDir == null ? "desc" : sortDir.trim().toLowerCase(Locale.ROOT);
        if ("asc".equals(normalized)) {
            return Sort.Direction.ASC;
        }
        if ("desc".equals(normalized)) {
            return Sort.Direction.DESC;
        }
        throw new IllegalArgumentException("sortDir must be ASC or DESC");
    }
}
