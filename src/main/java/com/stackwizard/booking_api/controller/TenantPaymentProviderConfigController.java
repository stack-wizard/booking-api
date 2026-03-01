package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.TenantPaymentProviderConfig;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.TenantPaymentProviderConfigService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/tenant-payment-provider-configs")
public class TenantPaymentProviderConfigController {
    private final TenantPaymentProviderConfigService service;

    public TenantPaymentProviderConfigController(TenantPaymentProviderConfigService service) {
        this.service = service;
    }

    @GetMapping
    public List<TenantPaymentProviderConfig> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<TenantPaymentProviderConfig> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TenantPaymentProviderConfig> create(@RequestBody TenantPaymentProviderConfig config) {
        Long tenantId = TenantResolver.requireTenantId(config.getTenantId());
        config.setTenantId(tenantId);
        TenantPaymentProviderConfig saved = service.save(config);
        return ResponseEntity.created(URI.create("/api/tenant-payment-provider-configs/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<TenantPaymentProviderConfig> update(@PathVariable Long id,
                                                              @RequestBody TenantPaymentProviderConfig config) {
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
