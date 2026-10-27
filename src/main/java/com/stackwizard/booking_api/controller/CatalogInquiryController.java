package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CatalogInquiryRequest;
import com.stackwizard.booking_api.dto.CatalogInquiryResponse;
import com.stackwizard.booking_api.service.CatalogInquiryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Website (CMS) MICE inquiries. Called only with the CMS m2m token and X-Tenant-Id; lands as a WEBSITE lead in CRM.
 * 201 for a new lead, 200 when the same {@code cmsInquiryId} was already submitted.
 */
@RestController
@RequestMapping("/api/catalog/inquiries")
public class CatalogInquiryController {
    private final CatalogInquiryService service;

    public CatalogInquiryController(CatalogInquiryService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<CatalogInquiryResponse> submit(@Valid @RequestBody CatalogInquiryRequest request) {
        CatalogInquiryService.Result result = service.submit(request);
        CatalogInquiryResponse body = new CatalogInquiryResponse(result.lead().getId(), result.lead().getStatus().name());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(body);
    }
}
