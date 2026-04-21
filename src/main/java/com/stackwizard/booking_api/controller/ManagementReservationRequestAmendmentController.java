package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ReservationRequestAmendmentApplyResponse;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentMutateRequest;
import com.stackwizard.booking_api.dto.ReservationRequestAmendmentPreviewResponse;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ReservationAmendmentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/management/reservation-requests")
public class ManagementReservationRequestAmendmentController {

    private final ReservationAmendmentService reservationAmendmentService;

    public ManagementReservationRequestAmendmentController(ReservationAmendmentService reservationAmendmentService) {
        this.reservationAmendmentService = reservationAmendmentService;
    }

    @PostMapping("/{id}/amendments/preview")
    public ReservationRequestAmendmentPreviewResponse preview(
            @RequestParam Long tenantId,
            @PathVariable("id") Long reservationRequestId,
            @Valid @RequestBody ReservationRequestAmendmentMutateRequest body) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return reservationAmendmentService.preview(resolved, reservationRequestId, body);
    }

    @PostMapping("/{id}/amendments/apply")
    public ReservationRequestAmendmentApplyResponse apply(
            @RequestParam Long tenantId,
            @PathVariable("id") Long reservationRequestId,
            @Valid @RequestBody ReservationRequestAmendmentMutateRequest body) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return reservationAmendmentService.apply(resolved, reservationRequestId, body);
    }
}
