package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.service.PriceListEntryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/price-list")
public class PriceListEntryController {
    private final PriceListEntryService service;

    public PriceListEntryController(PriceListEntryService service) { this.service = service; }

    @GetMapping
    public List<PriceListEntry> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<PriceListEntry> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PriceListEntry> create(@RequestBody PriceListEntry entry) {
        PriceListEntry saved = service.save(entry);
        return ResponseEntity.created(URI.create("/api/price-list/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PriceListEntry> update(@PathVariable Long id, @RequestBody PriceListEntry entry) {
        entry.setId(id);
        return ResponseEntity.ok(service.save(entry));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
