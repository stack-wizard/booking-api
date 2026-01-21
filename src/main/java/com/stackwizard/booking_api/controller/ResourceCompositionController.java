package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.service.ResourceCompositionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/resource-compositions")
public class ResourceCompositionController {
    private final ResourceCompositionService service;

    public ResourceCompositionController(ResourceCompositionService service) { this.service = service; }

    @GetMapping
    public List<ResourceComposition> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceComposition> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ResourceComposition> create(@RequestBody ResourceComposition rc) {
        ResourceComposition saved = service.save(rc);
        return ResponseEntity.created(URI.create("/api/resource-compositions/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceComposition> update(@PathVariable Long id, @RequestBody ResourceComposition rc) {
        rc.setId(id);
        return ResponseEntity.ok(service.save(rc));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
