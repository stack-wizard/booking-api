package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.service.PriceCheckService;
import com.stackwizard.booking_api.service.PriceListEntryResolver;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;
import java.time.LocalDate;
import java.time.LocalTime;
import com.stackwizard.booking_api.dto.ProductPriceDtos;
import com.stackwizard.booking_api.service.ProductPriceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductPriceController {
    private final ProductPriceService service;
    private final PriceCheckService priceCheckService;

    public ProductPriceController(ProductPriceService service, PriceCheckService priceCheckService) {
        this.service = service;
        this.priceCheckService = priceCheckService;
    }

    @GetMapping("/{id}/price-check")
    public PriceListEntryResolver.PriceCheck priceCheck(@PathVariable Long id,
                                                        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                                        @RequestParam(required = false) String uom,
                                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime time,
                                                        @RequestParam(required = false) ReservationRequest.Type requestType,
                                                        @RequestParam(required = false) String currency) {
        return priceCheckService.check(id, date, uom, time, requestType, currency);
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
