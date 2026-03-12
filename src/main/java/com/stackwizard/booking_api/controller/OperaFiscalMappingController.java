package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.OperaFiscalChargeMapping;
import com.stackwizard.booking_api.model.OperaFiscalPaymentMapping;
import com.stackwizard.booking_api.model.OperaFiscalTaxMapping;
import com.stackwizard.booking_api.model.OperaFiscalUdfMapping;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.fiscal.OperaFiscalMappingService;
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
@RequestMapping("/api/fiscal/mappings")
public class OperaFiscalMappingController {
    private final OperaFiscalMappingService service;

    public OperaFiscalMappingController(OperaFiscalMappingService service) {
        this.service = service;
    }

    @GetMapping("/charges")
    public List<OperaFiscalChargeMapping> chargeMappings(@RequestParam(required = false) Long tenantId) {
        return service.findChargeMappings(tenantId != null ? TenantResolver.requireTenantId(tenantId) : null);
    }

    @PostMapping("/charges")
    public ResponseEntity<OperaFiscalChargeMapping> createChargeMapping(@RequestBody OperaFiscalChargeMapping mapping) {
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        OperaFiscalChargeMapping saved = service.saveChargeMapping(mapping);
        return ResponseEntity.created(URI.create("/api/fiscal/mappings/charges/" + saved.getId())).body(saved);
    }

    @PutMapping("/charges/{id}")
    public ResponseEntity<OperaFiscalChargeMapping> updateChargeMapping(@PathVariable Long id,
                                                                        @RequestBody OperaFiscalChargeMapping mapping) {
        mapping.setId(id);
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        return ResponseEntity.ok(service.saveChargeMapping(mapping));
    }

    @DeleteMapping("/charges/{id}")
    public ResponseEntity<Void> deleteChargeMapping(@PathVariable Long id) {
        service.deleteChargeMapping(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/payments")
    public List<OperaFiscalPaymentMapping> paymentMappings(@RequestParam(required = false) Long tenantId) {
        return service.findPaymentMappings(tenantId != null ? TenantResolver.requireTenantId(tenantId) : null);
    }

    @PostMapping("/payments")
    public ResponseEntity<OperaFiscalPaymentMapping> createPaymentMapping(@RequestBody OperaFiscalPaymentMapping mapping) {
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        OperaFiscalPaymentMapping saved = service.savePaymentMapping(mapping);
        return ResponseEntity.created(URI.create("/api/fiscal/mappings/payments/" + saved.getId())).body(saved);
    }

    @PutMapping("/payments/{id}")
    public ResponseEntity<OperaFiscalPaymentMapping> updatePaymentMapping(@PathVariable Long id,
                                                                          @RequestBody OperaFiscalPaymentMapping mapping) {
        mapping.setId(id);
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        return ResponseEntity.ok(service.savePaymentMapping(mapping));
    }

    @DeleteMapping("/payments/{id}")
    public ResponseEntity<Void> deletePaymentMapping(@PathVariable Long id) {
        service.deletePaymentMapping(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/taxes")
    public List<OperaFiscalTaxMapping> taxMappings(@RequestParam(required = false) Long tenantId) {
        return service.findTaxMappings(tenantId != null ? TenantResolver.requireTenantId(tenantId) : null);
    }

    @PostMapping("/taxes")
    public ResponseEntity<OperaFiscalTaxMapping> createTaxMapping(@RequestBody OperaFiscalTaxMapping mapping) {
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        OperaFiscalTaxMapping saved = service.saveTaxMapping(mapping);
        return ResponseEntity.created(URI.create("/api/fiscal/mappings/taxes/" + saved.getId())).body(saved);
    }

    @PutMapping("/taxes/{id}")
    public ResponseEntity<OperaFiscalTaxMapping> updateTaxMapping(@PathVariable Long id,
                                                                  @RequestBody OperaFiscalTaxMapping mapping) {
        mapping.setId(id);
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        return ResponseEntity.ok(service.saveTaxMapping(mapping));
    }

    @DeleteMapping("/taxes/{id}")
    public ResponseEntity<Void> deleteTaxMapping(@PathVariable Long id) {
        service.deleteTaxMapping(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/udfs")
    public List<OperaFiscalUdfMapping> udfMappings(@RequestParam(required = false) Long tenantId) {
        return service.findUdfMappings(tenantId != null ? TenantResolver.requireTenantId(tenantId) : null);
    }

    @PostMapping("/udfs")
    public ResponseEntity<OperaFiscalUdfMapping> createUdfMapping(@RequestBody OperaFiscalUdfMapping mapping) {
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        OperaFiscalUdfMapping saved = service.saveUdfMapping(mapping);
        return ResponseEntity.created(URI.create("/api/fiscal/mappings/udfs/" + saved.getId())).body(saved);
    }

    @PutMapping("/udfs/{id}")
    public ResponseEntity<OperaFiscalUdfMapping> updateUdfMapping(@PathVariable Long id,
                                                                  @RequestBody OperaFiscalUdfMapping mapping) {
        mapping.setId(id);
        mapping.setTenantId(TenantResolver.requireTenantId(mapping.getTenantId()));
        return ResponseEntity.ok(service.saveUdfMapping(mapping));
    }

    @DeleteMapping("/udfs/{id}")
    public ResponseEntity<Void> deleteUdfMapping(@PathVariable Long id) {
        service.deleteUdfMapping(id);
        return ResponseEntity.noContent().build();
    }
}
