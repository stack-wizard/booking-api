package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.BookingOperaPosting;
import com.stackwizard.booking_api.service.BookingOperaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings/{bookingId}/opera")
public class BookingOperaController {
    private final BookingOperaService service;

    public BookingOperaController(BookingOperaService service) {
        this.service = service;
    }

    @GetMapping
    public OperaDtos.BookingOpera view(@PathVariable Long bookingId) {
        return service.view(bookingId);
    }

    @PostMapping("/sync")
    public OperaDtos.BookingOpera sync(@PathVariable Long bookingId) {
        return service.sync(bookingId);
    }

    @PostMapping("/post-charges")
    public List<BookingOperaPosting> postCharges(@PathVariable Long bookingId) {
        return service.postCharges(bookingId);
    }
}
