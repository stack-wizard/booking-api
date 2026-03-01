package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.DepositPolicy;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.DepositPolicyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/deposit-policies")
public class DepositPolicyController {
    private final DepositPolicyService service;

    public DepositPolicyController(DepositPolicyService service) {
        this.service = service;
    }

    @GetMapping
    public List<DepositPolicy> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DepositPolicy> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<DepositPolicy> create(@RequestBody DepositPolicy policy) {
        Long tenantId = TenantResolver.requireTenantId(policy.getTenantId());
        policy.setTenantId(tenantId);
        DepositPolicy saved = service.save(policy);
        return ResponseEntity.created(URI.create("/api/deposit-policies/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<DepositPolicy> update(@PathVariable Long id, @RequestBody DepositPolicy policy) {
        policy.setId(id);
        if (policy.getTenantId() != null) {
            Long tenantId = TenantResolver.requireTenantId(policy.getTenantId());
            policy.setTenantId(tenantId);
        }
        return ResponseEntity.ok(service.save(policy));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
