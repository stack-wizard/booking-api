package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.service.AllocationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/allocations")
public class AllocationController {
    private final AllocationService service;

    public AllocationController(AllocationService service) { this.service = service; }

    @GetMapping
    public List<Allocation> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Allocation> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Allocation> create(@RequestBody Allocation a) {
        Allocation saved = service.save(a);
        return ResponseEntity.created(URI.create("/api/allocations/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Allocation> update(@PathVariable Long id, @RequestBody Allocation a) {
        a.setId(id);
        return ResponseEntity.ok(service.save(a));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
