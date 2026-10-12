package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.service.CrmLookupService;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/crm/lookups")
public class CrmLookupController {
    private final CrmLookupService service;

    public CrmLookupController(CrmLookupService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmLookup> list(@RequestParam CrmLookup.Kind kind) {
        return service.list(kind);
    }

    @PostMapping
    public ResponseEntity<CrmLookup> create(@RequestBody CrmLookup entry) {
        CrmLookup saved = service.create(entry);
        return ResponseEntity.created(URI.create("/api/crm/lookups/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmLookup update(@PathVariable Long id, @RequestBody CrmLookup entry) {
        return service.update(id, entry);
    }
}
