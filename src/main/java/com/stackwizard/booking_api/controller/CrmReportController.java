package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CrmReportDtos;
import com.stackwizard.booking_api.service.CrmReportService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/crm/reports")
public class CrmReportController {
    private final CrmReportService service;

    public CrmReportController(CrmReportService service) {
        this.service = service;
    }

    @GetMapping("/funnel")
    public CrmReportDtos.FunnelResponse funnel(@RequestParam Long pipelineId,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                               @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                               @RequestParam(required = false) Long ownerUserId) {
        return service.funnel(pipelineId, from, to, ownerUserId);
    }

    @GetMapping("/conversion")
    public CrmReportDtos.ConversionResponse conversion(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.conversion(from, to);
    }

    @GetMapping("/stage-duration")
    public CrmReportDtos.StageDurationResponse stageDuration(
            @RequestParam Long pipelineId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.stageDuration(pipelineId, from, to);
    }
}
