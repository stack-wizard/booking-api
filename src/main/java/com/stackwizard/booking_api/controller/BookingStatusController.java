package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.BookingStatusDtos;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.service.BookingStatusService;
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
@RequestMapping("/api/crm/booking-statuses")
public class BookingStatusController {
    private final BookingStatusService service;

    public BookingStatusController(BookingStatusService service) {
        this.service = service;
    }

    @GetMapping
    public List<BookingStatusDtos.Status> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<BookingStatusDtos.Status> create(@RequestBody BookingStatusDtos.Status status) {
        BookingStatusDtos.Status saved = service.create(status);
        return ResponseEntity.created(URI.create("/api/crm/booking-statuses/" + saved.getId())).body(saved);
    }

    @PutMapping("/{id}")
    public BookingStatusDtos.Status update(@PathVariable Long id, @RequestBody BookingStatusDtos.Status status) {
        return service.update(id, status);
    }

    @GetMapping("/opera-hotels")
    public List<OperaHotel> operaHotels() {
        return service.operaHotels();
    }

    @PostMapping("/import-opera")
    public BookingStatusDtos.OperaImportResult importFromOpera(@RequestParam Long operaHotelId) {
        return service.importFromOpera(operaHotelId);
    }
}
