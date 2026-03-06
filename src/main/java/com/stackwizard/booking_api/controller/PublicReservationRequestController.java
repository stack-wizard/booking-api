package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ReservationRequestDto;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.service.ReservationRequestDtoMapper;
import com.stackwizard.booking_api.service.ReservationRequestService;
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

    public PublicReservationRequestController(ReservationRequestService reservationRequestService,
                                              ReservationRequestDtoMapper dtoMapper) {
        this.reservationRequestService = reservationRequestService;
        this.dtoMapper = dtoMapper;
    }

    @GetMapping("/access/{token}")
    public ResponseEntity<ReservationRequestDto> byAccessToken(@PathVariable String token) {
        ReservationRequest request = reservationRequestService.findByPublicAccessToken(token);
        return ResponseEntity.ok(dtoMapper.toDto(request));
    }
}
