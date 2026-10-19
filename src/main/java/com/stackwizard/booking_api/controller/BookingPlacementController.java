package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.service.BookingFitService;
import com.stackwizard.booking_api.service.BookingRoomBlockService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/bookings")
public class BookingPlacementController {
    private final BookingRoomBlockService service;
    private final BookingFitService fitService;

    public BookingPlacementController(BookingRoomBlockService service, BookingFitService fitService) {
        this.service = service;
        this.fitService = fitService;
    }

    @GetMapping("/{id}/fit")
    public BookingDtos.FitResult fit(@PathVariable Long id) {
        return fitService.fit(id);
    }

    @GetMapping("/{id}/room-blocks")
    public List<BookingDtos.RoomBlockView> blocks(@PathVariable Long id) {
        return service.blocks(id);
    }

    @PostMapping("/{id}/room-blocks")
    public BookingDtos.RoomBlockView create(@PathVariable Long id, @RequestBody BookingDtos.RoomBlockRequest request) {
        return service.create(id, request);
    }

    @PutMapping("/room-blocks/{blockId}")
    public BookingDtos.RoomBlockView update(@PathVariable Long blockId, @RequestBody BookingDtos.RoomBlockRequest request) {
        return service.update(blockId, request);
    }

    @PutMapping("/room-blocks/{blockId}/nights")
    public BookingDtos.RoomBlockView nights(@PathVariable Long blockId, @RequestBody List<BookingDtos.RoomNight> nights) {
        return service.setNights(blockId, nights);
    }

    @GetMapping("/room-blocks/{blockId}/placement-availability")
    public BookingDtos.PlacementAvailability placementAvailability(
            @PathVariable Long blockId,
            @RequestParam(required = false) Long propertyTenantId) {
        return service.placementAvailability(blockId, propertyTenantId);
    }

    @PostMapping("/room-blocks/{blockId}/place")
    public BookingDtos.RoomBlockView place(@PathVariable Long blockId, @RequestBody BookingDtos.PlaceRoomBlockRequest request) {
        return service.place(blockId, request.getRoomResourceId());
    }

    @PostMapping("/room-blocks/{blockId}/unplace")
    public BookingDtos.RoomBlockView unplace(@PathVariable Long blockId) {
        return service.unplace(blockId);
    }

    @DeleteMapping("/room-blocks/{blockId}")
    public ResponseEntity<Void> delete(@PathVariable Long blockId) {
        service.delete(blockId);
        return ResponseEntity.noContent().build();
    }
}
