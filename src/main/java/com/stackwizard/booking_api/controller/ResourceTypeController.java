package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.service.ResourceTypeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/resource-types")
public class ResourceTypeController {
    private final ResourceTypeService service;

    public ResourceTypeController(ResourceTypeService service) {
        this.service = service;
    }

    @GetMapping
    public List<ResourceType> all() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceType> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ResourceType> create(@RequestBody ResourceType rt) {
        try {

            ResourceType saved = service.save(rt);
            return ResponseEntity.created(URI.create("/api/resource-types/" + saved.getId())).body(saved);
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceType> update(@PathVariable Long id, @RequestBody ResourceType rt) {
        try {
            return ResponseEntity.ok(service.save(rt));
        } catch (Exception e) {
            return ResponseEntity.status(500).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
