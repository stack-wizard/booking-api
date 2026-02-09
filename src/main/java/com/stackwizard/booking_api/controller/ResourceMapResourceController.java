package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ResourceMapResourceService;
import com.stackwizard.booking_api.service.ResourceMapService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/resource-map-resources")
public class ResourceMapResourceController {
    private final ResourceMapResourceService service;
    private final ResourceMapService mapService;

    public ResourceMapResourceController(ResourceMapResourceService service, ResourceMapService mapService) {
        this.service = service;
        this.mapService = mapService;
    }

    @GetMapping
    public List<ResourceMapResource> all(@RequestParam(required = false) Long resourceMapId,
                                         @RequestParam(required = false) Long resourceId) {
        if (resourceMapId != null) {
            return service.findByMapId(resourceMapId);
        }
        if (resourceId != null) {
            return service.findByResourceId(resourceId);
        }
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceMapResource> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ResourceMapResource> create(@RequestBody ResourceMapResource mapResource) {
        ResourceMap map = requireMap(mapResource);
        Long tenantId = TenantResolver.requireTenantId(map.getTenantId());
        if (!tenantId.equals(map.getTenantId())) {
            throw new IllegalArgumentException("tenantId does not match map tenant");
        }
        ResourceMapResource saved = service.save(mapResource);
        return ResponseEntity.created(URI.create("/api/resource-map-resources/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceMapResource> update(@PathVariable Long id,
                                                      @RequestBody ResourceMapResource mapResource) {
        mapResource.setId(id);
        ResourceMap map = requireMap(mapResource);
        Long tenantId = TenantResolver.requireTenantId(map.getTenantId());
        if (!tenantId.equals(map.getTenantId())) {
            throw new IllegalArgumentException("tenantId does not match map tenant");
        }
        return ResponseEntity.ok(service.save(mapResource));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private ResourceMap requireMap(ResourceMapResource mapResource) {
        if (mapResource.getResourceMap() == null || mapResource.getResourceMap().getId() == null) {
            throw new IllegalArgumentException("resourceMap.id is required");
        }
        return mapService.findById(mapResource.getResourceMap().getId())
                .orElseThrow(() -> new IllegalArgumentException("Map not found"));
    }
}
