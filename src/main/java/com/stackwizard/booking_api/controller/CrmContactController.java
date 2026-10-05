package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.service.CrmContactService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/contacts")
public class CrmContactController {
    private final CrmContactService service;

    public CrmContactController(CrmContactService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmContact> all() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CrmContact> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CrmContact> create(@RequestBody CrmContact contact) {
        CrmContact saved = service.create(contact);
        return ResponseEntity.created(URI.create("/api/crm/contacts/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmContact update(@PathVariable Long id, @RequestBody CrmContact contact) {
        return service.update(id, contact);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }
}
