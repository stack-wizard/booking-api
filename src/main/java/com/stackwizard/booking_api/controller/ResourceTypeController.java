package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.service.ResourceTypeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
