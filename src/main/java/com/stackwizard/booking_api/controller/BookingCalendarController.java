package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.service.BookingCalendarService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/booking-calendars")
public class BookingCalendarController {
    private final BookingCalendarService service;

    public BookingCalendarController(BookingCalendarService service) { this.service = service; }

    @GetMapping
    public List<BookingCalendar> all() { return service.findAll(); }

    @GetMapping("/{id}")
    public ResponseEntity<BookingCalendar> get(@PathVariable Long id) {
        return service.findById(id).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<BookingCalendar> create(@RequestBody BookingCalendar calendar) {
        BookingCalendar saved = service.save(calendar);
        return ResponseEntity.created(URI.create("/api/booking-calendars/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BookingCalendar> update(@PathVariable Long id, @RequestBody BookingCalendar calendar) {
        calendar.setId(id);
        return ResponseEntity.ok(service.save(calendar));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.deleteById(id); return ResponseEntity.noContent().build(); }
}
