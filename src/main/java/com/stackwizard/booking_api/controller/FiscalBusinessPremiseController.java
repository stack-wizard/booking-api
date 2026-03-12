package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.FiscalBusinessPremise;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.FiscalBusinessPremiseService;
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
@RequestMapping("/api/fiscal/business-premises")
public class FiscalBusinessPremiseController {
    private final FiscalBusinessPremiseService service;

    public FiscalBusinessPremiseController(FiscalBusinessPremiseService service) {
        this.service = service;
    }

    @GetMapping
    public List<FiscalBusinessPremise> all(@RequestParam(required = false) Long tenantId) {
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FiscalBusinessPremise> get(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FiscalBusinessPremise> create(@RequestBody FiscalBusinessPremise premise) {
        Long tenantId = TenantResolver.requireTenantId(premise.getTenantId());
        premise.setTenantId(tenantId);
        FiscalBusinessPremise saved = service.save(premise);
        return ResponseEntity.created(URI.create("/api/fiscal/business-premises/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FiscalBusinessPremise> update(@PathVariable Long id,
                                                        @RequestBody FiscalBusinessPremise premise) {
        premise.setId(id);
        Long tenantId = TenantResolver.requireTenantId(premise.getTenantId());
        premise.setTenantId(tenantId);
        return ResponseEntity.ok(service.save(premise));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
