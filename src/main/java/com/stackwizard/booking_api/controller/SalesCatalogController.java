package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.CatalogDtos;
import com.stackwizard.booking_api.model.CatalogItem;
import com.stackwizard.booking_api.model.SalesCatalog;
import com.stackwizard.booking_api.service.SalesCatalogService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Admin: sales catalogs, their items and maps (user JWT). */
@RestController
@RequestMapping("/api")
public class SalesCatalogController {
    private final SalesCatalogService service;

    public SalesCatalogController(SalesCatalogService service) {
        this.service = service;
    }

    @GetMapping("/sales-catalogs")
    public List<SalesCatalog> catalogs() {
        return service.catalogs();
    }

    @GetMapping("/sales-catalogs/{id}")
    public SalesCatalog catalog(@PathVariable Long id) {
        return service.catalog(id);
    }

    @PostMapping("/sales-catalogs")
    public ResponseEntity<SalesCatalog> create(@RequestBody CatalogDtos.CatalogInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(input));
    }

    @PutMapping("/sales-catalogs/{id}")
    public SalesCatalog update(@PathVariable Long id, @RequestBody CatalogDtos.CatalogInput input) {
        return service.update(id, input);
    }

    @DeleteMapping("/sales-catalogs/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sales-catalogs/{id}/items")
    public List<CatalogDtos.AdminItem> items(@PathVariable Long id,
                                             @RequestParam(required = false) String group,
                                             @RequestParam(required = false) Boolean published) {
        return service.items(id, group, published);
    }

    @PostMapping("/sales-catalogs/{id}/items")
    public ResponseEntity<CatalogItem> addItem(@PathVariable Long id, @RequestBody CatalogItem input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.addItem(id, input));
    }

    @PostMapping("/sales-catalogs/{id}/items/bulk")
    public CatalogDtos.BulkResult bulk(@PathVariable Long id, @RequestBody CatalogDtos.BulkRequest request) {
        return service.bulk(id, request);
    }

    @PatchMapping("/sales-catalogs/{id}/items/order")
    public List<CatalogItem> reorder(@PathVariable Long id, @RequestBody List<Long> itemIds) {
        return service.reorder(id, itemIds);
    }

    @GetMapping("/sales-catalogs/{id}/maps")
    public List<CatalogDtos.MapLink> maps(@PathVariable Long id) {
        return service.maps(id);
    }

    @PutMapping("/sales-catalogs/{id}/maps")
    public List<CatalogDtos.MapLink> replaceMaps(@PathVariable Long id, @RequestBody List<CatalogDtos.MapLink> links) {
        return service.replaceMaps(id, links);
    }

    @GetMapping("/sales-catalogs/{id}/preview")
    public CatalogDtos.Preview preview(@PathVariable Long id,
                                       @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                       @RequestParam(required = false) Integer pax) {
        return service.preview(id, date, pax);
    }

    @PutMapping("/catalog-items/{id}")
    public CatalogItem updateItem(@PathVariable Long id, @RequestBody CatalogItem input) {
        return service.updateItem(id, input);
    }

    @DeleteMapping("/catalog-items/{id}")
    public ResponseEntity<Void> deleteItem(@PathVariable Long id) {
        service.deleteItem(id);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/catalog-items/{id}/seasons")
    public List<Long> itemSeasons(@PathVariable Long id, @RequestBody List<Long> seasonIds) {
        return service.setItemSeasons(id, seasonIds);
    }

    @GetMapping("/products/{id}/catalog-items")
    public List<CatalogDtos.ProductCatalogItem> productCatalogItems(@PathVariable Long id) {
        return service.productCatalogItems(id);
    }
}
