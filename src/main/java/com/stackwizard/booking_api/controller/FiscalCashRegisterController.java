package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.FiscalCashRegister;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.FiscalCashRegisterService;
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
@RequestMapping("/api/fiscal/cash-registers")
public class FiscalCashRegisterController {
    private final FiscalCashRegisterService service;

    public FiscalCashRegisterController(FiscalCashRegisterService service) {
        this.service = service;
    }

    @GetMapping
    public List<FiscalCashRegister> all(@RequestParam(required = false) Long tenantId,
                                        @RequestParam(required = false) Long businessPremiseId) {
        if (businessPremiseId != null) {
            return service.findByBusinessPremiseId(businessPremiseId);
        }
        if (tenantId == null) {
            return service.findAll();
        }
        Long resolvedTenantId = TenantResolver.requireTenantId(tenantId);
        return service.findByTenantId(resolvedTenantId);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FiscalCashRegister> get(@PathVariable Long id) {
        return service.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FiscalCashRegister> create(@RequestBody FiscalCashRegister register) {
        Long tenantId = TenantResolver.requireTenantId(register.getTenantId());
        register.setTenantId(tenantId);
        FiscalCashRegister saved = service.save(register);
        return ResponseEntity.created(URI.create("/api/fiscal/cash-registers/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<FiscalCashRegister> update(@PathVariable Long id,
                                                     @RequestBody FiscalCashRegister register) {
        register.setId(id);
        Long tenantId = TenantResolver.requireTenantId(register.getTenantId());
        register.setTenantId(tenantId);
        return ResponseEntity.ok(service.save(register));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
