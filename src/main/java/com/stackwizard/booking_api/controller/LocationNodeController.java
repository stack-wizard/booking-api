package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.service.LocationNodeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/location-nodes")
public class LocationNodeController {
    private final LocationNodeService service;

    public LocationNodeController(LocationNodeService service) { this.service = service; }

    @GetMapping
    public List<LocationNode> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<LocationNode> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<LocationNode> create(@RequestBody LocationNode n) {
        LocationNode saved = service.save(n);
        return ResponseEntity.created(URI.create("/api/location-nodes/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<LocationNode> update(@PathVariable Long id, @RequestBody LocationNode n) {
        n.setId(id);
        return ResponseEntity.ok(service.save(n));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
