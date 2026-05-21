package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementStayDashboardDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementStayDashboardResponse;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ManagementStayDashboardExportService;
import com.stackwizard.booking_api.service.ManagementStayDashboardService;
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

/**
 * Stay dashboard: checked-in / checked-out counts from reservations overlapping the selected
 * window (request status drives which bucket), and revenue from issued invoice lines linked to a
 * stay ({@code reservation_request_id} set), excluding {@code DEPOSIT} and {@code DEPOSIT_STORNO}
 * documents. Product breakdown uses {@code invoice_item.product_id} (not reservation.product_id).
 */
@RestController
@RequestMapping("/api/management/stay-dashboard")
public class ManagementStayDashboardController {

    private final ManagementStayDashboardService stayDashboardService;
    private final ManagementStayDashboardExportService stayDashboardExportService;

    public ManagementStayDashboardController(ManagementStayDashboardService stayDashboardService,
                                            ManagementStayDashboardExportService stayDashboardExportService) {
        this.stayDashboardService = stayDashboardService;
        this.stayDashboardExportService = stayDashboardExportService;
    }

    @GetMapping
    public ManagementStayDashboardResponse getStayDashboard(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return stayDashboardService.getStayDashboard(resolved, from, to);
    }

    @GetMapping("/daily-trend")
    public ManagementStayDashboardDailyTrendResponse getDailyTrend(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        return stayDashboardService.getDailyTrend(resolved, from, to);
    }

    @GetMapping("/export/revenue-by-product")
    public ResponseEntity<byte[]> exportRevenueByProduct(
            @RequestParam Long tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        Long resolved = TenantResolver.requireTenantId(tenantId);
        byte[] bytes = stayDashboardExportService.exportRevenueByProduct(resolved, from, to);
        String dateStamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(OffsetDateTime.now());
        String filename = "stay-dashboard-revenue-by-product-" + dateStamp + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
    }
}
