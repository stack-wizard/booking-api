package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ReservationRequestCustomerPatchRequest;
import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationTtlExtendRequest;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestService;
import com.stackwizard.booking_api.service.ReservationService;
import com.stackwizard.booking_api.service.TenantConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reservation-requests")
public class ReservationRequestController {
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
}
