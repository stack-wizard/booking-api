package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.dto.ReservationTtlExtendRequest;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationRequestService;
import com.stackwizard.booking_api.service.ReservationService;
import com.stackwizard.booking_api.service.TenantConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/reservation-requests")
public class ReservationRequestController {
    private final ReservationRequestService service;
    private final ReservationService reservationService;
    private final TenantConfigService tenantConfigService;
    private final ProductRepository productRepository;

    public ReservationRequestController(ReservationRequestService service,
                                        ReservationService reservationService,
                                        TenantConfigService tenantConfigService,
                                        ProductRepository productRepository) {
        this.service = service;
        this.reservationService = reservationService;
        this.tenantConfigService = tenantConfigService;
        this.productRepository = productRepository;
    }

    @GetMapping
    public List<ReservationRequest> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<ReservationRequestDto> get(@PathVariable Long id) {
        return service.findById(id)
                .map(request -> ResponseEntity.ok(toDto(request, reservationService.findByRequestId(id))))
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
        return ResponseEntity.ok(toDto(updated, reservationService.findByRequestId(id)));
    }

    @PostMapping("/{id}/finalize")
    public ResponseEntity<Void> finalizeRequest(@PathVariable Long id) {
        reservationService.finalizeRequest(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteDraftRequest(id);
        return ResponseEntity.noContent().build();
    }

    private ReservationRequestDto toDto(ReservationRequest request, List<Reservation> reservations) {
        Map<Long, String> productNames = productRepository.findAllById(
                        reservations.stream()
                                .map(Reservation::getProductId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, Product::getName));

        List<ReservationSummaryDto> reservationDtos = reservations.stream()
                .map(reservation -> ReservationSummaryDto.builder()
                        .id(reservation.getId())
                        .tenantId(reservation.getTenantId())
                        .productId(reservation.getProductId())
                        .productName(productNames.get(reservation.getProductId()))
                        .requestId(reservation.getRequest() != null ? reservation.getRequest().getId() : null)
                        .requestType(reservation.getRequestType() != null ? reservation.getRequestType().name() : null)
                        .requestedResourceId(reservation.getRequestedResource() != null ? reservation.getRequestedResource().getId() : null)
                        .startsAt(reservation.getStartsAt())
                        .endsAt(reservation.getEndsAt())
                        .status(reservation.getStatus())
                        .expiresAt(reservation.getExpiresAt())
                        .adults(reservation.getAdults())
                        .children(reservation.getChildren())
                        .infants(reservation.getInfants())
                        .customerName(reservation.getCustomerName())
                        .build())
                .toList();

        return ReservationRequestDto.builder()
                .id(request.getId())
                .tenantId(request.getTenantId())
                .type(request.getType() != null ? request.getType().name() : null)
                .status(request.getStatus() != null ? request.getStatus().name() : null)
                .createdAt(request.getCreatedAt())
                .expiresAt(request.getExpiresAt())
                .extensionCount(request.getExtensionCount())
                .reservations(reservationDtos)
                .build();
    }
}
