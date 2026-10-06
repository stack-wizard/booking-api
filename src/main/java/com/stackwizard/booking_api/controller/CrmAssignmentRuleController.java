package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmAssignmentRule;
import com.stackwizard.booking_api.service.CrmAssignmentRuleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/assignment-rules")
public class CrmAssignmentRuleController {
    private final CrmAssignmentRuleService service;

    public CrmAssignmentRuleController(CrmAssignmentRuleService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmAssignmentRule> all() {
        return service.findAll();
    }

    @PostMapping
    public ResponseEntity<CrmAssignmentRule> create(@RequestBody CrmAssignmentRule rule) {
        CrmAssignmentRule saved = service.create(rule);
        return ResponseEntity.created(URI.create("/api/crm/assignment-rules/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmAssignmentRule update(@PathVariable Long id, @RequestBody CrmAssignmentRule rule) {
        return service.update(id, rule);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
