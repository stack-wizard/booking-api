package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.PriceProfileDate;
import com.stackwizard.booking_api.service.PriceProfileDateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/price-profile-dates")
public class PriceProfileDateController {
    private final PriceProfileDateService service;

    public PriceProfileDateController(PriceProfileDateService service) { this.service = service; }

    @GetMapping
    public List<PriceProfileDate> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<PriceProfileDate> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PriceProfileDate> create(@RequestBody PriceProfileDate profileDate) {
        PriceProfileDate saved = service.save(profileDate);
        return ResponseEntity.created(URI.create("/api/price-profile-dates/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PriceProfileDate> update(@PathVariable Long id, @RequestBody PriceProfileDate profileDate) {
        profileDate.setId(id);
        return ResponseEntity.ok(service.save(profileDate));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
