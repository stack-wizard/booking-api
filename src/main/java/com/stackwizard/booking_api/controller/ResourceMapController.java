package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.ResourceMapService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@RestController
@RequestMapping("/api/resource-maps")
public class ResourceMapController {
    private static final Path MAP_DIR = Paths.get("data", "maps");

    private final ResourceMapService service;

    public ResourceMapController(ResourceMapService service) { this.service = service; }

    @GetMapping
    public List<ResourceMap> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
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
                                                   @RequestParam("file") MultipartFile file) throws IOException {
        ResourceMap map = service.findById(id).orElseThrow(() -> new IllegalArgumentException("Map not found"));
        if (file.isEmpty()) {
            throw new IllegalArgumentException("File is required");
        }

        Files.createDirectories(MAP_DIR);
        String ext = extensionOrDefault(file.getOriginalFilename(), "png");
        String filename = "map-" + id + "." + ext;
        Path target = MAP_DIR.resolve(filename);
        Files.write(target, file.getBytes());

        map.setImageUrl("/maps/" + filename);
        ResourceMap saved = service.save(map);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    private String extensionOrDefault(String name, String defaultExt) {
        if (name == null) {
            return defaultExt;
        }
        int idx = name.lastIndexOf('.');
        if (idx < 0 || idx == name.length() - 1) {
            return defaultExt;
        }
        String ext = name.substring(idx + 1).toLowerCase();
        return ext.isBlank() ? defaultExt : ext;
    }
}
