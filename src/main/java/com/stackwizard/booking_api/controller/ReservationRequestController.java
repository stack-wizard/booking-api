package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CancellationExecuteRequest;
import com.stackwizard.booking_api.dto.CancellationRequestDto;
import com.stackwizard.booking_api.dto.CheckinReadinessDto;
import com.stackwizard.booking_api.dto.CheckinResultDto;
import com.stackwizard.booking_api.dto.CheckoutReadinessDto;
import com.stackwizard.booking_api.dto.CheckoutResultDto;
import com.stackwizard.booking_api.dto.ReservationRequestCustomerPatchRequest;
import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationTtlExtendRequest;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestExportService;
import com.stackwizard.booking_api.service.ReservationConfirmationEmailService;
import com.stackwizard.booking_api.service.CancellationService;
import com.stackwizard.booking_api.service.ReservationRequestService;
import com.stackwizard.booking_api.service.ReservationService;
import com.stackwizard.booking_api.service.ReservationStayService;
import com.stackwizard.booking_api.service.TenantConfigService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/reservation-requests")
public class ReservationRequestController {
    private static final int MAX_PAGE_SIZE = 200;

    private final ReservationRequestService service;
    private final ReservationService reservationService;
    private final CancellationService cancellationService;
    private final ObjectProvider<ReservationConfirmationEmailService> reservationConfirmationEmailServiceProvider;
    private final TenantConfigService tenantConfigService;
    private final ReservationRequestDtoMapper dtoMapper;
    private final ReservationRequestExportService exportService;
    private final ReservationStayService reservationStayService;

    public ReservationRequestController(ReservationRequestService service,
                                        ReservationService reservationService,
                                        CancellationService cancellationService,
                                        ObjectProvider<ReservationConfirmationEmailService> reservationConfirmationEmailServiceProvider,
                                        TenantConfigService tenantConfigService,
                                        ReservationRequestDtoMapper dtoMapper,
                                        ReservationRequestExportService exportService,
                                        ReservationStayService reservationStayService) {
        this.service = service;
        this.reservationService = reservationService;
        this.cancellationService = cancellationService;
        this.reservationConfirmationEmailServiceProvider = reservationConfirmationEmailServiceProvider;
        this.tenantConfigService = tenantConfigService;
        this.dtoMapper = dtoMapper;
        this.exportService = exportService;
        this.reservationStayService = reservationStayService;
    }

    /**
     * Full list for admin (e.g. Payments page). Uses {@link ReservationRequestDto} so payment totals and status match
     * {@link #search} and {@link #get} (including rules such as excluding cancelled reservation lines from amounts).
     */
    @GetMapping
    public List<ReservationRequestDto> all() {
        return service.findAll().stream().map(dtoMapper::toDto).toList();
    }

    @GetMapping("/search")
    public Page<ReservationRequestDto> search(ReservationRequestSearchCriteria criteria,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(defaultValue = "createdAt") String sortBy,
                                              @RequestParam(defaultValue = "desc") String sortDir) {
        Pageable pageable = buildPageable(page, size, sortBy, sortDir);
        return service.search(criteria, pageable).map(dtoMapper::toDto);
    }

    @GetMapping(value = "/search/export", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> exportSearch(ReservationRequestSearchCriteria criteria,
                                               @RequestParam(defaultValue = "createdAt") String sortBy,
                                               @RequestParam(defaultValue = "desc") String sortDir) {
        byte[] workbook = exportService.exportSearch(criteria, buildSort(sortBy, sortDir));
        String fileName = "reservation-requests-export-"
                + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now()) + ".xlsx";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .body(workbook);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationRequestDto> get(@PathVariable Long id) {
        return service.findById(id)
                .map(request -> ResponseEntity.ok(dtoMapper.toDto(request)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ReservationRequest> create(@RequestBody ReservationRequest request) {
        Long tenantId = TenantResolver.requireTenantId(request.getTenantId());
        request.setTenantId(tenantId);
        if (request.getType() == null) {
            request.setType(ReservationRequest.Type.EXTERNAL);
        }
        if (request.getStatus() == null) {
            request.setStatus(ReservationRequest.Status.DRAFT);
        }
        if (request.getExpiresAt() == null && requiresDefaultExpiry(request)) {
            int minutes = request.getStatus() == ReservationRequest.Status.MANUAL_REVIEW
                    ? tenantConfigService.manualReviewTtlMinutes(tenantId)
                    : tenantConfigService.holdTtlMinutes(tenantId);
            request.setExpiresAt(OffsetDateTime.now().plusMinutes(minutes));
        }
        if (request.getExtensionCount() == null) {
            request.setExtensionCount(0);
        }
        ReservationRequest saved = service.save(request);
        return ResponseEntity.created(URI.create("/api/reservation-requests/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ReservationRequestDto> replaceDraft(@PathVariable Long id, @RequestBody ReservationRequest body) {
        ReservationRequest updated = service.replaceDraftReservationRequest(id, body);
        return ResponseEntity.ok(dtoMapper.toDto(updated));
    }

    @PostMapping("/{id}/extend")
    public ResponseEntity<ReservationRequestDto> extendRequest(@PathVariable Long id,
                                                               @RequestBody(required = false) ReservationTtlExtendRequest body) {
        Integer minutes = body != null ? body.getMinutes() : null;
        ReservationRequest updated = reservationService.extendRequestTtl(id, minutes);
        return ResponseEntity.ok(dtoMapper.toDto(updated));
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<Void> finalizeRequest(@PathVariable Long id) {
        reservationService.finalizeRequest(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/check-in-readiness")
    public ResponseEntity<CheckinReadinessDto> checkInReadiness(@PathVariable Long id) {
        return ResponseEntity.ok(reservationStayService.getCheckinReadiness(id));
    }

    @GetMapping("/{id}/checkout-readiness")
    public ResponseEntity<CheckoutReadinessDto> checkoutReadiness(@PathVariable Long id) {
        return ResponseEntity.ok(reservationStayService.getCheckoutReadiness(id));
    }

    @PostMapping("/{id}/check-in")
    public ResponseEntity<CheckinResultDto> checkIn(@PathVariable Long id) {
        return ResponseEntity.ok(reservationStayService.checkIn(id));
    }

    @PostMapping("/{id}/check-out")
    public ResponseEntity<CheckoutResultDto> checkOut(@PathVariable Long id) {
        return ResponseEntity.ok(reservationStayService.checkOut(id));
    }

    @PostMapping("/{id}/send-confirmation-email")
    public ResponseEntity<ReservationConfirmationEmailService.DispatchResult> sendConfirmationEmail(
            @PathVariable Long id,
            @RequestParam(defaultValue = "false") boolean force) {
        ReservationConfirmationEmailService service = reservationConfirmationEmailServiceProvider.getIfAvailable();
        if (service == null) {
            throw new IllegalStateException("Reservation confirmation email feature is not available");
        }
        return ResponseEntity.ok(service.sendForDebug(id, force));
    }

    @PatchMapping("/{id}/customer")
    public ResponseEntity<ReservationRequestDto> patchCustomer(@PathVariable Long id,
                                                               @RequestBody ReservationRequestCustomerPatchRequest request) {
        ReservationRequest updated = service.updateCustomerData(
                id,
                request != null ? request.getCustomerName() : null,
                request != null ? request.getCustomerEmail() : null,
                request != null ? request.getCustomerPhone() : null
        );
        return ResponseEntity.ok(dtoMapper.toDto(updated));
    }

    @PostMapping("/{id}/cancel-payment")
    public ResponseEntity<ReservationRequestDto> cancelPayment(@PathVariable Long id) {
        ReservationRequest updated = service.cancelPaymentForRequest(id);
        return ResponseEntity.ok(dtoMapper.toDto(updated));
    }

    @GetMapping("/{id}/cancellations")
    public ResponseEntity<List<CancellationRequestDto>> cancellations(@PathVariable Long id) {
        return ResponseEntity.ok(cancellationService.findByReservationRequestId(id));
    }

    @PostMapping("/{id}/cancellations")
    public ResponseEntity<CancellationRequestDto> createCancellation(@PathVariable Long id,
                                                                     @RequestBody(required = false) CancellationExecuteRequest request) {
        return ResponseEntity.ok(cancellationService.execute(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteDraftRequest(id);
        return ResponseEntity.noContent().build();
    }

    private Pageable buildPageable(int page, int size, String sortBy, String sortDir) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_PAGE_SIZE);
        }

        return PageRequest.of(page, size, buildSort(sortBy, sortDir));
    }

    private Sort buildSort(String sortBy, String sortDir) {
        String sortProperty = resolveSortProperty(sortBy);
        Sort.Direction direction = resolveDirection(sortDir);
        return Sort.by(direction, sortProperty);
    }

    private String resolveSortProperty(String sortBy) {
        String raw = sortBy == null ? "createdAt" : sortBy.trim();
        String normalized = raw.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "id", "requestid", "reservationrequestid" -> "id";
            case "createdat", "created_at" -> "createdAt";
            case "expiresat", "expires_at" -> "expiresAt";
            case "confirmedat", "confirmed_at" -> "confirmedAt";
            case "status" -> "status";
            case "type" -> "type";
            case "customername", "customer_name" -> "customerName";
            case "customeremail", "customer_email" -> "customerEmail";
            case "customerphone", "customer_phone" -> "customerPhone";
            case "notes" -> "notes";
            case "externalreservation", "external_reservation" -> "externalReservation";
            case "confirmationcode", "confirmationnumber", "confirmation_number", "confirmation_code" -> "confirmationCode";
            case "extensioncount", "extension_count" -> "extensionCount";
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

    private boolean requiresDefaultExpiry(ReservationRequest request) {
        return request.getStatus() == ReservationRequest.Status.DRAFT
                ? request.getType() != ReservationRequest.Type.INTERNAL
                : request.getStatus() == ReservationRequest.Status.PENDING_PAYMENT
                || request.getStatus() == ReservationRequest.Status.MANUAL_REVIEW;
    }
}
