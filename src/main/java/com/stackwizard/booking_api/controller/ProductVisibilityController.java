package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ProductVisibilityDtos;
import com.stackwizard.booking_api.service.ProductVisibilityService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Per-hotel visibility of a chain product. */
@RestController
@RequestMapping("/api/products/{productId}/properties")
public class ProductVisibilityController {
    private final ProductVisibilityService service;

    public ProductVisibilityController(ProductVisibilityService service) {
        this.service = service;
    }

    @GetMapping
    public ProductVisibilityDtos.Response get(@PathVariable Long productId) {
        return service.get(productId);
    }

    @PutMapping
    public ProductVisibilityDtos.Response update(@PathVariable Long productId,
                                                 @RequestBody ProductVisibilityDtos.Request request) {
        return service.update(productId, request);
    }
}
