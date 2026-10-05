package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmCustomFieldDefinition;
import com.stackwizard.booking_api.service.CrmCustomFieldDefinitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/custom-fields")
public class CrmCustomFieldDefinitionController {
    private final CrmCustomFieldDefinitionService service;

    public CrmCustomFieldDefinitionController(CrmCustomFieldDefinitionService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmCustomFieldDefinition> all() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    public ResponseEntity<CrmCustomFieldDefinition> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<CrmCustomFieldDefinition> create(@RequestBody CrmCustomFieldDefinition def) {
        CrmCustomFieldDefinition saved = service.create(def);
        return ResponseEntity.created(URI.create("/api/crm/custom-fields/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmCustomFieldDefinition update(@PathVariable Long id, @RequestBody CrmCustomFieldDefinition def) {
        return service.update(id, def);
    }
}
