package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.dto.ReservationSummaryDto;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import com.stackwizard.booking_api.repository.ProductRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Component
public class ReservationRequestDtoMapper {
    private final ReservationService reservationService;
    private final ProductRepository productRepository;
    private final PaymentService paymentService;
    private final ReservationRequestAccessTokenService accessTokenService;

    public ReservationRequestDtoMapper(ReservationService reservationService,
                                       ProductRepository productRepository,
                                       PaymentService paymentService,
                                       ReservationRequestAccessTokenService accessTokenService) {
        this.reservationService = reservationService;
        this.productRepository = productRepository;
        this.paymentService = paymentService;
        this.accessTokenService = accessTokenService;
    }

    public ReservationRequestDto toDto(ReservationRequest request) {
        List<Reservation> reservations = reservationService.findByRequestId(request.getId());
        LocalDateTime reservationStartsAt = reservations.stream()
                .map(Reservation::getStartsAt)
                .filter(Objects::nonNull)
                .min(LocalDateTime::compareTo)
                .orElse(null);
        LocalDateTime reservationEndsAt = reservations.stream()
                .map(Reservation::getEndsAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);

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
                        .requestedResourceCode(reservation.getRequestedResource() != null ? reservation.getRequestedResource().getCode() : null)
                        .requestedResourceName(reservation.getRequestedResource() != null ? reservation.getRequestedResource().getName() : null)
                        .startsAt(reservation.getStartsAt())
                        .endsAt(reservation.getEndsAt())
                        .status(reservation.getStatus())
                        .expiresAt(reservation.getExpiresAt())
                        .adults(reservation.getAdults())
                        .children(reservation.getChildren())
                        .infants(reservation.getInfants())
                        .customerName(reservation.getCustomerName())
                        .customerEmail(reservation.getCustomerEmail())
                        .customerPhone(reservation.getCustomerPhone())
                        .currency(reservation.getCurrency())
                        .qty(reservation.getQty())
                        .unitPrice(reservation.getUnitPrice())
                        .grossAmount(reservation.getGrossAmount())
                        .cancellationPolicyText(reservation.getCancellationPolicyText())
                        .build())
                .toList();

        PaymentService.RequestPaymentSummary paymentSummary =
                paymentService.summarizeReservationRequest(request.getId(), request.getTenantId(), reservations);

        ReservationRequestAccessToken accessToken = accessTokenService.findActiveByReservationRequestId(request.getId()).orElse(null);
        String publicAccessUrl = accessToken != null
                ? accessTokenService.buildPublicAccessUrl(accessToken.getTenantId(), accessToken.getToken())
                : null;

        return ReservationRequestDto.builder()
                .id(request.getId())
                .tenantId(request.getTenantId())
                .type(request.getType() != null ? request.getType().name() : null)
                .status(request.getStatus() != null ? request.getStatus().name() : null)
                .createdAt(request.getCreatedAt())
                .expiresAt(request.getExpiresAt())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .customerPhone(request.getCustomerPhone())
                .cancellationPolicyText(request.getCancellationPolicyText())
                .notes(request.getNotes())
                .externalReservation(request.getExternalReservation())
                .confirmationCode(request.getConfirmationCode())
                .confirmedAt(request.getConfirmedAt())
                .publicAccessUrl(publicAccessUrl)
                .publicAccessExpiresAt(accessToken != null ? accessToken.getExpiresAt() : null)
                .qrPayload(publicAccessUrl)
                .extensionCount(request.getExtensionCount())
                .paymentTotalAmount(paymentSummary.totalAmount())
                .paymentDueNowAmount(paymentSummary.dueNowAmount())
                .paymentPaidAmount(paymentSummary.paidAmount())
                .paymentRemainingAmount(paymentSummary.remainingAmount())
                .paymentStatus(paymentSummary.paymentStatus())
                .reservationStartsAt(reservationStartsAt)
                .reservationEndsAt(reservationEndsAt)
                .reservations(reservationDtos)
                .build();
    }
}
