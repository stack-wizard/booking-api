package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementForecastDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementForecastResponse;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ManagementForecastExportService;
import com.stackwizard.booking_api.service.ManagementForecastService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;

@RestController
@RequestMapping("/api/management/forecast")
public class ManagementForecastController {

    private final ManagementForecastService forecastService;
    private final ManagementForecastExportService forecastExportService;

    public ManagementForecastController(ManagementForecastService forecastService,
                                        ManagementForecastExportService forecastExportService) {
        this.forecastService = forecastService;
        this.forecastExportService = forecastExportService;
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

    @GetMapping("/export/revenue-by-product")
    public ResponseEntity<byte[]> exportRevenueByProduct(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        byte[] bytes = forecastExportService.exportRevenueByProduct(resolved, from, to);
        String dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now());
        String filename = "forecast-revenue-by-product-" + dateStamp + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
