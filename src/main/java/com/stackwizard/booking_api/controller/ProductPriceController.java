package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ProductPriceDtos;
import com.stackwizard.booking_api.service.ProductPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductPriceController {
    private final ProductPriceService service;

    public ProductPriceController(ProductPriceService service) {
        this.service = service;
    }

    @GetMapping("/price-periods")
    public List<ProductPriceDtos.Period> periods() {
        return service.periods();
    }

    @GetMapping("/{id}/prices")
    public List<ProductPriceDtos.PriceRow> prices(@PathVariable Long id) {
        return service.prices(id);
    }

    @PutMapping("/{id}/prices")
    public List<ProductPriceDtos.PriceRow> save(@PathVariable Long id, @RequestBody ProductPriceDtos.SaveRequest request) {
        return service.save(id, request);
    }

    @PostMapping("/{id}/prices/adjust")
    public List<ProductPriceDtos.PriceRow> adjust(@PathVariable Long id, @RequestBody ProductPriceDtos.AdjustRequest request) {
        return service.adjust(id, request);
    }

    @PostMapping("/{id}/prices/copy")
    public List<ProductPriceDtos.PriceRow> copy(@PathVariable Long id, @RequestBody ProductPriceDtos.CopyRequest request) {
        return service.copy(id, request);
    }
}
