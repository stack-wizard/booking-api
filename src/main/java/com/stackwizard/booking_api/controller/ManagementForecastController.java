package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementForecastDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementForecastResponse;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ManagementForecastService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

@RestController
@RequestMapping("/api/management/forecast")
public class ManagementForecastController {

    private final ManagementForecastService forecastService;

    public ManagementForecastController(ManagementForecastService forecastService) {
        this.forecastService = forecastService;
    }

    @GetMapping
    public ManagementForecastResponse getForecast(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return forecastService.getForecast(resolved, from, to);
    }

    @GetMapping("/daily-trend")
    public ManagementForecastDailyTrendResponse getDailyTrend(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return forecastService.getDailyTrend(resolved, from, to);
    }
}
