package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.BookingRequest;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @GetMapping
    public List<Reservation> all(@RequestParam(required = false) Long requestId) {
        if (requestId != null) {
            return service.findByRequestId(requestId);
        }
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<Reservation> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Reservation> create(@RequestBody Reservation r) {
        Long tenantId = TenantResolver.requireTenantId(r.getTenantId());
        r.setTenantId(tenantId);
        if (r.getRequest() == null || r.getRequest().getId() == null) {
            throw new IllegalArgumentException("request.id is required");
        }
        if (r.getRequestType() == null) {
            r.setRequestType(ReservationRequest.Type.EXTERNAL);
        }
        List<Allocation> allocations = service.createReservationAndAllocate(r);
        Reservation saved = allocations.isEmpty() ? null : allocations.get(0).getReservation();
        if (saved == null) {
            throw new IllegalStateException("Allocation not created");
        }
        return ResponseEntity.created(URI.create("/api/reservations/" + saved.getId())).body(saved);
    }

    @PostMapping("/book")
    public ResponseEntity<List<Allocation>> book(@RequestBody BookingRequest request) {
        List<Allocation> allocations = service.createBooking(request);
        return ResponseEntity.ok(allocations);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Reservation> update(@PathVariable Long id, @RequestBody Reservation r) {
        r.setId(id);
        if (r.getTenantId() != null) {
            Long tenantId = TenantResolver.requireTenantId(r.getTenantId());
            r.setTenantId(tenantId);
        }
        if (r.getRequest() == null || r.getRequest().getId() == null) {
            throw new IllegalArgumentException("request.id is required");
        }
        if (r.getRequestType() == null) {
            r.setRequestType(ReservationRequest.Type.EXTERNAL);
        }
        boolean existingFound = service.findById(id)
                .map(existing -> {
                    if (existing.getExpiresAt() != null && existing.getExpiresAt().isBefore(OffsetDateTime.now())) {
                        throw new IllegalStateException("Reservation expired");
                    }
                    r.setStatus(existing.getStatus());
                    r.setExpiresAt(existing.getExpiresAt());
                    return true;
                })
                .orElse(false);
        if (!existingFound) {
            return ResponseEntity.ok(service.saveHoldReservation(r));
        }
        return ResponseEntity.ok(service.save(r));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteReservationDraftOnly(id);
        return ResponseEntity.noContent().build();
    }
}
