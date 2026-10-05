package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.FunctionSpace;
import com.stackwizard.booking_api.model.FunctionSpaceSetup;
import com.stackwizard.booking_api.service.FunctionSpaceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/crm/function-spaces")
public class FunctionSpaceController {
    private final FunctionSpaceService service;

    public FunctionSpaceController(FunctionSpaceService service) {
        this.service = service;
    }

    @GetMapping
    public List<FunctionSpace> all(@RequestParam(required = false) Boolean active) {
        return service.findAll(active);
    }

    @GetMapping("/{id}")
    public ResponseEntity<FunctionSpace> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<FunctionSpace> create(@RequestBody FunctionSpace space) {
        FunctionSpace saved = service.create(space);
        return ResponseEntity.created(URI.create("/api/crm/function-spaces/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public FunctionSpace update(@PathVariable Long id, @RequestBody FunctionSpace space) {
        return service.update(id, space);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.softDelete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/setups")
    public List<FunctionSpaceSetup> setups(@PathVariable Long id) {
        return service.setups(id);
    }

    @PostMapping("/{id}/setups")
    public ResponseEntity<FunctionSpaceSetup> createSetup(@PathVariable Long id,
                                                          @RequestBody FunctionSpaceSetup setup) {
        FunctionSpaceSetup saved = service.createSetup(id, setup);
        return ResponseEntity.created(URI.create("/api/crm/function-spaces/" + id + "/setups/" + saved.getId()))
                .body(saved);
    }

    @PutMapping("/setups/{setupId}")
    public FunctionSpaceSetup updateSetup(@PathVariable Long setupId, @RequestBody FunctionSpaceSetup setup) {
        return service.updateSetup(setupId, setup);
    }

    @DeleteMapping("/setups/{setupId}")
    public ResponseEntity<Void> deleteSetup(@PathVariable Long setupId) {
        service.deleteSetup(setupId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/setups/by-capacity")
    public List<FunctionSpaceSetup> byCapacity(@RequestParam Integer minPax) {
        return service.findSetupsWithCapacity(minPax);
    }
}
