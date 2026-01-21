package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.service.PriceProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/price-profiles")
public class PriceProfileController {
    private final PriceProfileService service;

    public PriceProfileController(PriceProfileService service) { this.service = service; }

    @GetMapping
    public List<PriceProfile> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<PriceProfile> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<PriceProfile> create(@RequestBody PriceProfile profile) {
        PriceProfile saved = service.save(profile);
        return ResponseEntity.created(URI.create("/api/price-profiles/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<PriceProfile> update(@PathVariable Long id, @RequestBody PriceProfile profile) {
        profile.setId(id);
        return ResponseEntity.ok(service.save(profile));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
