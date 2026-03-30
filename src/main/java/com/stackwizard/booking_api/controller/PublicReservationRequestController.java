package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CancellationRequestDto;
import com.stackwizard.booking_api.dto.PublicCancellationConfirmRequest;
import com.stackwizard.booking_api.dto.PublicCancellationPreviewDto;
import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.service.CancellationService;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/public/reservation-requests", "/booking-api/api/public/reservation-requests"})
public class PublicReservationRequestController {
    private final ReservationRequestService reservationRequestService;
    private final ReservationRequestDtoMapper dtoMapper;
    private final CancellationService cancellationService;

    public PublicReservationRequestController(ReservationRequestService reservationRequestService,
                                              ReservationRequestDtoMapper dtoMapper,
                                              CancellationService cancellationService) {
        this.reservationRequestService = reservationRequestService;
        this.dtoMapper = dtoMapper;
        this.cancellationService = cancellationService;
    }

    @GetMapping("/access/{token}")
    public ResponseEntity<ReservationRequestDto> byAccessToken(@PathVariable String token) {
        ReservationRequest request = reservationRequestService.findByPublicAccessToken(token);
        ReservationRequestDto dto = dtoMapper.toDto(request);
        dto.setPublicCancellation(cancellationService.previewPublic(request.getId()));
        return ResponseEntity.ok(dto);
    }

    @GetMapping("/access/{token}/cancellation")
    public ResponseEntity<PublicCancellationPreviewDto> cancellationPreview(@PathVariable String token) {
        ReservationRequest request = reservationRequestService.findByPublicAccessToken(token);
        return ResponseEntity.ok(cancellationService.previewPublic(request.getId()));
    }

    @PostMapping("/access/{token}/cancellation")
    public ResponseEntity<CancellationRequestDto> cancelByAccessToken(@PathVariable String token,
                                                                      @RequestBody(required = false) PublicCancellationConfirmRequest request) {
        ReservationRequest reservationRequest = reservationRequestService.findByPublicAccessToken(token);
        com.stackwizard.booking_api.dto.CancellationExecuteRequest executeRequest =
                new com.stackwizard.booking_api.dto.CancellationExecuteRequest();
        executeRequest.setNote(request != null ? request.getNote() : null);
        return ResponseEntity.ok(cancellationService.executePublic(reservationRequest.getId(), executeRequest));
    }
}
