package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.PaymentCardType;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.PaymentCardTypeService;
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
@RequestMapping("/api/payment-card-types")
public class PaymentCardTypeController {
    private final PaymentCardTypeService service;

    public PaymentCardTypeController(PaymentCardTypeService service) {
        this.service = service;
    }

    @GetMapping
    public List<PaymentCardType> all(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantId != null ? TenantResolver.requireTenantId(tenantId) : null;
        return service.findAll(resolvedTenantId);
    }

    @PostMapping
    public ResponseEntity<PaymentCardType> create(@RequestBody PaymentCardType cardType) {
        cardType.setTenantId(TenantResolver.requireTenantId(cardType.getTenantId()));
        PaymentCardType saved = service.save(cardType);
        return ResponseEntity.created(URI.create("/api/payment-card-types/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PaymentCardType> update(@PathVariable Long id, @RequestBody PaymentCardType cardType) {
        cardType.setId(id);
        cardType.setTenantId(TenantResolver.requireTenantId(cardType.getTenantId()));
        return ResponseEntity.ok(service.save(cardType));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
