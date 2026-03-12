package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.TenantIntegrationConfig;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.TenantIntegrationConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping({"/api/tenant-integration-configs", "/api/tenant-payment-provider-configs"})
public class TenantIntegrationConfigController {
    private final TenantIntegrationConfigService service;

    public TenantIntegrationConfigController(TenantIntegrationConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<TenantIntegrationConfig> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantIntegrationConfig> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TenantIntegrationConfig> create(@RequestBody TenantIntegrationConfig config) {
        Long tenantId = TenantResolver.requireTenantId(config.getTenantId());
        config.setTenantId(tenantId);
        TenantIntegrationConfig saved = service.save(config);
        return ResponseEntity.created(URI.create("/api/tenant-integration-configs/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantIntegrationConfig> update(@PathVariable Long id,
                                                          @RequestBody TenantIntegrationConfig config) {
        config.setId(id);
        if (config.getTenantId() != null) {
            Long tenantId = TenantResolver.requireTenantId(config.getTenantId());
            config.setTenantId(tenantId);
        }
        return ResponseEntity.ok(service.save(config));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
