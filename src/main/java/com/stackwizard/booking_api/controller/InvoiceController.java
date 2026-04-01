package com.stackwizard.booking_api.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.dto.InvoiceSearchCriteria;
import com.stackwizard.booking_api.dto.InvoiceIssueRequest;
import com.stackwizard.booking_api.dto.InvoiceFiscalizeRequest;
import com.stackwizard.booking_api.dto.InvoicePaymentAllocationRequest;
import com.stackwizard.booking_api.dto.OperaInvoicePostRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.IssuedByMode;
import com.stackwizard.booking_api.service.InvoicePdfService;
import com.stackwizard.booking_api.service.InvoiceService;
import com.stackwizard.booking_api.service.fiscal.InvoiceFiscalizationService;
import com.stackwizard.booking_api.service.opera.OperaInvoicePostingResult;
import com.stackwizard.booking_api.service.opera.OperaInvoicePostingService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
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
    private final InvoicePdfService invoicePdfService;
    private final InvoiceFiscalizationService invoiceFiscalizationService;
    private final OperaInvoicePostingService operaInvoicePostingService;

    public InvoiceController(InvoiceService invoiceService,
                             InvoicePdfService invoicePdfService,
                             InvoiceFiscalizationService invoiceFiscalizationService,
                             OperaInvoicePostingService operaInvoicePostingService) {
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
        this.invoiceFiscalizationService = invoiceFiscalizationService;
        this.operaInvoicePostingService = operaInvoicePostingService;
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

    @PostMapping
    public ResponseEntity<Map<String, Object>> createInvoice(@RequestBody InvoiceCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Long reservationRequestId = request.getReservationRequestId() != null
                ? request.getReservationRequestId()
                : request.getRequestId();
        Invoice invoice = reservationRequestId != null
                ? invoiceService.createDraftForFinalizedRequest(reservationRequestId)
                : invoiceService.createManualDraft(request);
        return toResponse(invoice);
    }

    @PutMapping("/{invoiceId}")
    public ResponseEntity<Map<String, Object>> updateDraftInvoice(@PathVariable Long invoiceId,
                                                                  @RequestBody InvoiceCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice invoice = invoiceService.updateDraft(invoiceId, request);
        return toResponse(invoice);
    }

    @PostMapping("/{invoiceId}/allocations")
    public ResponseEntity<InvoicePaymentAllocation> allocatePayment(@PathVariable Long invoiceId,
                                                                    @RequestBody InvoicePaymentAllocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        InvoicePaymentAllocation allocation = invoiceService.allocatePaymentToInvoice(
                invoiceId, request.getPaymentTransactionId(), request.getAmount(), request.getAllocationType());
        return ResponseEntity.ok(allocation);
    }

    @PostMapping("/{invoiceId}/storno")
    public ResponseEntity<Map<String, Object>> createStorno(@PathVariable Long invoiceId) {
        Invoice storno = invoiceService.createStornoInvoice(invoiceId);
        return toResponse(storno);
    }

    @PostMapping("/{invoiceId}/issue")
    public ResponseEntity<Map<String, Object>> issueInvoice(@PathVariable Long invoiceId,
                                                            @RequestBody InvoiceIssueRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice issued = invoiceService.issueInvoice(invoiceId, request);
        if (issued.getFiscalizationStatus() == InvoiceFiscalizationStatus.REQUIRED) {
            InvoiceFiscalizeRequest fiscalizeRequest = new InvoiceFiscalizeRequest();
            fiscalizeRequest.setIssuedByMode(request.getIssuedByMode());
            fiscalizeRequest.setIssuedByUserId(request.getIssuedByUserId());
            fiscalizeRequest.setBusinessPremiseId(request.getBusinessPremiseId());
            fiscalizeRequest.setCashRegisterId(request.getCashRegisterId());
            Invoice fiscalized = invoiceFiscalizationService.tryFiscalizeInvoice(invoiceId, fiscalizeRequest);
            return toResponse(fiscalized);
        }
        return toResponse(issued);
    }

    @PostMapping("/{invoiceId}/fiscalize")
    public ResponseEntity<Map<String, Object>> fiscalizeInvoice(@PathVariable Long invoiceId,
                                                                @RequestBody InvoiceFiscalizeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice fiscalized = invoiceFiscalizationService.fiscalizeInvoice(invoiceId, request);
        return toResponse(fiscalized);
    }

    @PostMapping("/{invoiceId}/fiscalize/ofis-event")
    public ResponseEntity<Map<String, Object>> fiscalizeInvoiceWithOfisEvent(
            @PathVariable Long invoiceId,
            @RequestBody JsonNode ofisPayload,
            @RequestParam(required = false) IssuedByMode issuedByMode,
            @RequestParam(required = false) Long issuedByUserId,
            @RequestParam(required = false) Long businessPremiseId,
            @RequestParam(required = false) Long cashRegisterId) {
        if (ofisPayload == null || ofisPayload.isNull()) {
            throw new IllegalArgumentException("request body is required");
        }
        InvoiceFiscalizeRequest request = new InvoiceFiscalizeRequest();
        request.setOfisPayload(ofisPayload);
        request.setIssuedByMode(issuedByMode);
        request.setIssuedByUserId(issuedByUserId);
        request.setBusinessPremiseId(businessPremiseId);
        request.setCashRegisterId(cashRegisterId);

        Invoice fiscalized = invoiceFiscalizationService.fiscalizeInvoice(invoiceId, request);
        return toResponse(fiscalized);
    }

    @PostMapping(value = "/{invoiceId}/opera-post/preview", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> previewOperaPosting(@PathVariable Long invoiceId,
                                                      @RequestBody(required = false) OperaInvoicePostRequest request) {
        JsonNode payload = operaInvoicePostingService.previewPayload(invoiceId, request);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload != null ? payload.toPrettyString() : "{}");
    }

    @PostMapping("/{invoiceId}/opera-post")
    public ResponseEntity<OperaInvoicePostingResult> postToOpera(@PathVariable Long invoiceId,
                                                                 @RequestBody(required = false) OperaInvoicePostRequest request) {
        return ResponseEntity.ok(operaInvoicePostingService.postInvoice(invoiceId, request));
    }

    @GetMapping(value = "/{invoiceId}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> downloadInvoicePdf(@PathVariable Long invoiceId) {
        InvoicePdfService.InvoicePdfDocument document = invoicePdfService.generateInvoicePdf(invoiceId);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.fileName() + "\"")
                .body(document.content());
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
