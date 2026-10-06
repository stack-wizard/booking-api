package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.service.PackageCatalogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * Channel (CMS) view of published packages. Called with the CMS m2m token and X-Tenant-Id.
 */
@RestController
@RequestMapping("/api/catalog/packages")
public class PackageCatalogController {
    private final PackageCatalogService service;

    public PackageCatalogController(PackageCatalogService service) {
        this.service = service;
    }

    @GetMapping
    public List<PackageCatalogService.CatalogPackage> list(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String currency) {
        return service.catalog(date, currency);
    }

    @GetMapping("/{productId}")
    public PackageCatalogService.CatalogPackage get(
            @PathVariable Long productId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String currency) {
        return service.catalogPackage(productId, date, currency);
    }

    @GetMapping("/{productId}/availability")
    public PackageCatalogService.PackageAvailability availability(
            @PathVariable Long productId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam Integer pax,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime startTime,
            @RequestParam(required = false) String currency) {
        return service.availability(productId, date, pax, startTime, currency);
    }
}
