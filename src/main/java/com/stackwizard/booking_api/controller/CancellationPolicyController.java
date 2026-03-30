package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CancellationPolicy;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.CancellationPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/cancellation-policies")
public class CancellationPolicyController {
    private final CancellationPolicyService service;

    public CancellationPolicyController(CancellationPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<CancellationPolicy> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        return service.findByTenantId(TenantResolver.requireTenantId(tenantId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CancellationPolicy> get(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CancellationPolicy> create(@RequestBody CancellationPolicy policy) {
        policy.setTenantId(TenantResolver.requireTenantId(policy.getTenantId()));
        CancellationPolicy saved = service.save(policy);
        return ResponseEntity.created(URI.create("/api/cancellation-policies/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CancellationPolicy> update(@PathVariable Long id, @RequestBody CancellationPolicy policy) {
        policy.setId(id);
        if (policy.getTenantId() != null) {
            policy.setTenantId(TenantResolver.requireTenantId(policy.getTenantId()));
        }
        return ResponseEntity.ok(service.save(policy));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
