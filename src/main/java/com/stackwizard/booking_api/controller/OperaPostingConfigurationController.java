package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.model.OperaInvoiceTypeRouting;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.opera.OperaPostingConfigurationService;
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
@RequestMapping("/api/opera-posting-config")
public class OperaPostingConfigurationController {
    private final OperaPostingConfigurationService service;

    public OperaPostingConfigurationController(OperaPostingConfigurationService service) {
        this.service = service;
    }

    @GetMapping("/hotels")
    public List<OperaHotel> hotels(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantId != null ? TenantResolver.requireTenantId(tenantId) : null;
        return service.findHotels(resolvedTenantId);
    }

    @PostMapping("/hotels")
    public ResponseEntity<OperaHotel> createHotel(@RequestBody OperaHotel hotel) {
        hotel.setTenantId(TenantResolver.requireTenantId(hotel.getTenantId()));
        OperaHotel saved = service.saveHotel(hotel);
        return ResponseEntity.created(URI.create("/api/opera-posting-config/hotels/" + saved.getId())).body(saved);
    }

    @PutMapping("/hotels/{id}")
    public ResponseEntity<OperaHotel> updateHotel(@PathVariable Long id, @RequestBody OperaHotel hotel) {
        hotel.setId(id);
        hotel.setTenantId(TenantResolver.requireTenantId(hotel.getTenantId()));
        return ResponseEntity.ok(service.saveHotel(hotel));
    }

    @DeleteMapping("/hotels/{id}")
    public ResponseEntity<Void> deleteHotel(@PathVariable Long id) {
        service.deleteHotel(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/routings")
    public List<OperaInvoiceTypeRouting> routings(@RequestParam(required = false) Long tenantId) {
        Long resolvedTenantId = tenantId != null ? TenantResolver.requireTenantId(tenantId) : null;
        return service.findRoutings(resolvedTenantId);
    }

    @PostMapping("/routings")
    public ResponseEntity<OperaInvoiceTypeRouting> createRouting(@RequestBody OperaInvoiceTypeRouting routing) {
        routing.setTenantId(TenantResolver.requireTenantId(routing.getTenantId()));
        OperaInvoiceTypeRouting saved = service.saveRouting(routing);
        return ResponseEntity.created(URI.create("/api/opera-posting-config/routings/" + saved.getId())).body(saved);
    }

    @PutMapping("/routings/{id}")
    public ResponseEntity<OperaInvoiceTypeRouting> updateRouting(@PathVariable Long id,
                                                                 @RequestBody OperaInvoiceTypeRouting routing) {
        routing.setId(id);
        routing.setTenantId(TenantResolver.requireTenantId(routing.getTenantId()));
        return ResponseEntity.ok(service.saveRouting(routing));
    }

    @DeleteMapping("/routings/{id}")
    public ResponseEntity<Void> deleteRouting(@PathVariable Long id) {
        service.deleteRouting(id);
        return ResponseEntity.noContent().build();
    }
}
