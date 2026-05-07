package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.ResourceMapPeriodsResponse;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.MediaStorageService;
import com.stackwizard.booking_api.service.ResourceMapService;
import com.stackwizard.booking_api.support.BookingDevTimeSupport;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/resource-maps")
public class ResourceMapController {
    private final ResourceMapService service;
    private final MediaStorageService mediaStorageService;
    private final BookingDevTimeSupport devTimeSupport;

    public ResourceMapController(ResourceMapService service,
                                MediaStorageService mediaStorageService,
                                BookingDevTimeSupport devTimeSupport) {
        this.service = service;
        this.mediaStorageService = mediaStorageService;
        this.devTimeSupport = devTimeSupport;
    }

    @GetMapping
    public List<ResourceMap> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/periods")
    public ResourceMapPeriodsResponse periods(@RequestParam Long tenantId,
                                              @RequestParam(required = false) Long locationId,
                                              @RequestParam(required = false) LocalDate fromDate) {
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        LocalDate effectiveFromDate = devTimeSupport.resolvePeriodsFromDate(fromDate);
        return service.getMapPeriods(resolvedTenantId, locationId, effectiveFromDate);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ResourceMap> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ResourceMap> create(@RequestBody ResourceMap map) {
        Long tenantId = TenantResolver.requireTenantId(map.getTenantId());
        map.setTenantId(tenantId);
        ResourceMap saved = service.save(map);
        return ResponseEntity.created(URI.create("/api/resource-maps/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ResourceMap> update(@PathVariable Long id, @RequestBody ResourceMap map) {
        map.setId(id);
        if (map.getTenantId() != null) {
            Long tenantId = TenantResolver.requireTenantId(map.getTenantId());
            map.setTenantId(tenantId);
        }
        return ResponseEntity.ok(service.save(map));
    }

    @PostMapping(path = "/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResourceMap> uploadImage(@PathVariable Long id,
                                                   @RequestParam("file") MultipartFile file) {
        ResourceMap map = service.findById(id).orElseThrow(() -> new IllegalArgumentException("Map not found"));
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        Long tenantId = TenantResolver.requireTenantId(map.getTenantId());
        String imageUrl = mediaStorageService.uploadPublic("maps", tenantId, "map-" + id, file);

        map.setImageUrl(imageUrl);
        ResourceMap saved = service.save(map);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

}
