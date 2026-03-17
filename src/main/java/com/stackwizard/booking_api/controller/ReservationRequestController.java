package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ReservationRequestCustomerPatchRequest;
import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationRequestSearchCriteria;
import com.stackwizard.booking_api.dto.ReservationTtlExtendRequest;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestService;
import com.stackwizard.booking_api.service.ReservationService;
import com.stackwizard.booking_api.service.TenantConfigService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/reservation-requests")
public class ReservationRequestController {
    private static final int MAX_PAGE_SIZE = 200;

    private final ReservationRequestService service;
    private final ReservationService reservationService;
    private final TenantConfigService tenantConfigService;
    private final ReservationRequestDtoMapper dtoMapper;

    public ReservationRequestController(ReservationRequestService service,
                                        ReservationService reservationService,
                                        TenantConfigService tenantConfigService,
                                        ReservationRequestDtoMapper dtoMapper) {
        this.service = service;
        this.reservationService = reservationService;
        this.tenantConfigService = tenantConfigService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping
    public List<ReservationRequest> all() { return service.findAll(); }

    @GetMapping("/search")
    public Page<ReservationRequestDto> search(ReservationRequestSearchCriteria criteria,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size,
                                              @RequestParam(defaultValue = "createdAt") String sortBy,
                                              @RequestParam(defaultValue = "desc") String sortDir) {
        Pageable pageable = buildPageable(page, size, sortBy, sortDir);
        return service.search(criteria, pageable).map(dtoMapper::toDto);
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
        if (request.getExpiresAt() == null) {
            int minutes = tenantConfigService.holdTtlMinutes(tenantId);
            request.setExpiresAt(OffsetDateTime.now().plusMinutes(minutes));
        }
        if (request.getExtensionCount() == null) {
            request.setExtensionCount(0);
        }
        ReservationRequest saved = service.save(request);
        return ResponseEntity.created(URI.create("/api/reservation-requests/" + saved.getId())).body(saved);
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

        String sortProperty = resolveSortProperty(sortBy);
        Sort.Direction direction = resolveDirection(sortDir);
        return PageRequest.of(page, size, Sort.by(direction, sortProperty));
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
}
