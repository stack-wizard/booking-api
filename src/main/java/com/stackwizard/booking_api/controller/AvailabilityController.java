package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.AvailabilityResponse;
import com.stackwizard.booking_api.service.AvailabilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/availability")
public class AvailabilityController {
    private final AvailabilityService service;

    public AvailabilityController(AvailabilityService service) { this.service = service; }

    @GetMapping
    public AvailabilityResponse getAvailability(@RequestParam Long tenantId,
                                                @RequestParam LocalDate date,
                                                @RequestParam(required = false) Long locationId) {
        return service.getAvailability(tenantId, date, locationId);
    }
}
