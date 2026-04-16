package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ManagementStayDashboardDailyTrendResponse;
import com.stackwizard.booking_api.dto.ManagementStayDashboardResponse;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ManagementStayDashboardService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

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

    public ManagementStayDashboardController(ManagementStayDashboardService stayDashboardService) {
        this.stayDashboardService = stayDashboardService;
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
}
