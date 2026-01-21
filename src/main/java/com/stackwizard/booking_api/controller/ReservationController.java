package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.BookingRequest;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.service.ReservationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/reservations")
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @GetMapping
    public List<Reservation> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<Reservation> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Reservation> create(@RequestBody Reservation r) {
        Reservation saved = service.save(r);
        return ResponseEntity.created(URI.create("/api/reservations/" + saved.getId())).body(saved);
    }

    @PostMapping("/book")
    public ResponseEntity<List<Allocation>> book(@RequestBody BookingRequest request) {
        List<Allocation> allocations = service.createBooking(request);
        return ResponseEntity.ok(allocations);
    }

    @PutMapping("/{id}")
    public ResponseEntity<Reservation> update(@PathVariable Long id, @RequestBody Reservation r) {
        r.setId(id);
        return ResponseEntity.ok(service.save(r));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
