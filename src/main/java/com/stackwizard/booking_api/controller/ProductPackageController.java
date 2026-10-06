package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.model.ProductPackageListing;
import com.stackwizard.booking_api.service.PackageCatalogService;
import com.stackwizard.booking_api.service.PackageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductPackageController {
    private final PackageService service;
    private final PackageCatalogService catalogService;

    public ProductPackageController(PackageService service, PackageCatalogService catalogService) {
        this.service = service;
        this.catalogService = catalogService;
    }

    @PutMapping("/{id}/sales-group")
    public Product salesGroup(@PathVariable Long id, @RequestBody SalesGroupRequest request) {
        return service.setSalesGroup(id, request.salesGroup());
    }

    @GetMapping("/{id}/package-listing")
    public ProductPackageListing listing(@PathVariable Long id) {
        return catalogService.listing(id);
    }

    @PutMapping("/{id}/package-listing")
    public ProductPackageListing saveListing(@PathVariable Long id, @RequestBody ProductPackageListing request) {
        return catalogService.saveListing(id, request);
    }

    @GetMapping("/{id}/components")
    public List<ProductComponent> components(@PathVariable Long id) {
        return service.components(id);
    }

    @PutMapping("/{id}/package")
    public PackageService.PackageDefinition define(@PathVariable Long id, @RequestBody PackageDefinitionRequest request) {
        return service.define(id, request.packagePricing(), request.components());
    }

    @GetMapping("/{id}/package-price")
    public PackageService.PackageQuote quote(@PathVariable Long id,
                                             @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                             @RequestParam(required = false) Integer pax,
                                             @RequestParam(required = false) String currency) {
        return service.quote(id, date, pax, currency);
    }

    public record SalesGroupRequest(com.stackwizard.booking_api.model.SalesQuoteLine.Group salesGroup) {
    }

    public record PackageDefinitionRequest(Product.PackagePricing packagePricing, List<ProductComponent> components) {
    }
}
