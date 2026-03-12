package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ProductSelectionDto;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ProductService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) { this.service = service; }

    @GetMapping
    public List<Product> all() { return service.findAll(); }

    @GetMapping("/autocomplete")
    public List<Product> autocomplete(@RequestParam Long tenantId,
                                      @RequestParam(required = false) String q,
                                      @RequestParam(defaultValue = "20") int limit) {
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.autocomplete(resolvedTenantId, q, limit);
    }

    @GetMapping("/{id}/selection")
    public ResponseEntity<ProductSelectionDto> selection(@PathVariable Long id,
                                                         @RequestParam Long tenantId,
                                                         @RequestParam(required = false) String currency,
                                                         @RequestParam(required = false) String uom,
                                                         @RequestParam(required = false)
                                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return ResponseEntity.ok(service.selection(resolvedTenantId, id, currency, uom, date));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Product> create(@RequestBody Product product) {
        Product saved = service.save(product);
        return ResponseEntity.created(URI.create("/api/products/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Product> update(@PathVariable Long id, @RequestBody Product product) {
        product.setId(id);
        return ResponseEntity.ok(service.save(product));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
