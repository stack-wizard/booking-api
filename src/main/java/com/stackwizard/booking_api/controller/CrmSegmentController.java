package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.CrmSegment;
import com.stackwizard.booking_api.service.CrmSegmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/segments")
public class CrmSegmentController {
    private final CrmSegmentService service;

    public CrmSegmentController(CrmSegmentService service) {
        this.service = service;
    }

    @GetMapping
    public List<CrmSegment> all() {
        return service.findAll();
    }

    @PostMapping
    public ResponseEntity<CrmSegment> create(@RequestBody CrmSegment segment) {
        CrmSegment saved = service.create(segment);
        return ResponseEntity.created(URI.create("/api/crm/segments/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public CrmSegment update(@PathVariable Long id, @RequestBody CrmSegment segment) {
        return service.update(id, segment);
    }
}
