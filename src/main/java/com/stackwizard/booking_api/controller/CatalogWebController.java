package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.dto.CatalogItemsInquiryRequest;
import com.stackwizard.booking_api.service.CatalogItemsInquiryService;
import com.stackwizard.booking_api.service.CatalogWebService;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Sales catalogs for the website (booking-cms). m2m only: JWT with scope booking.api (ROLE_API, token_use=m2m) and
 * X-Tenant-Id (+ optional X-Property-Id). The tenant always comes from the context.
 */
@RestController
@RequestMapping("/api/catalogs")
public class CatalogWebController {
    private final CatalogWebService service;

    public CatalogWebController(CatalogWebService service) {
        this.service = service;
    }

    @GetMapping
    public List<CatalogDtos.CatalogSummary> catalogs() {
        return service.catalogs();
    }

    @GetMapping("/{code}")
    public CatalogDtos.CatalogDetail catalog(@PathVariable String code) {
        return service.catalog(code);
    }

    @GetMapping("/{code}/seasons")
    public List<CatalogDtos.SeasonView> seasons(@PathVariable String code,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                                @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.seasons(code, from, to);
    }

    @GetMapping("/{code}/items")
    public List<CatalogDtos.ItemView> items(@PathVariable String code,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
                                            @RequestParam(required = false) Integer pax,
                                            @RequestParam(required = false) String group) {
        return service.items(code, date, dateTo, pax, group);
    }

    @GetMapping("/{code}/maps")
    public List<CatalogDtos.MapView> maps(@PathVariable String code,
                                          @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.maps(code, date);
    }

    @PostMapping("/{code}/quote")
    public CatalogDtos.QuoteResponse quote(@PathVariable String code, @Valid @RequestBody CatalogDtos.QuoteRequest request) {
        return service.quote(code, request);
    }

    /** 201 for a new lead, 200 when the same {@code cmsInquiryId} was already submitted (then {@code quote} is null). */
    @PostMapping("/{code}/inquiries")
    public ResponseEntity<InquiryResponse> inquiry(@PathVariable String code, @Valid @RequestBody CatalogItemsInquiryRequest request) {
        CatalogItemsInquiryService.Result result = service.inquiry(code, request);
        InquiryResponse body = new InquiryResponse(result.lead().getId(), result.lead().getStatus().name(), result.quote());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }

    public record InquiryResponse(Long leadId, String status, CatalogDtos.QuoteResponse quote) {
    }
}
