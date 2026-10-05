package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.ProductComponent;
import com.stackwizard.booking_api.service.PackageService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductPackageController {
    private final PackageService service;

    public ProductPackageController(PackageService service) {
        this.service = service;
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

    public record PackageDefinitionRequest(Product.PackagePricing packagePricing, List<ProductComponent> components) {
    }
}
