package com.stackwizard.booking_api.controller;

import com.stackwizard.booking_api.dto.SeasonDtos;
import com.stackwizard.booking_api.service.SeasonService;
import org.springframework.http.HttpStatus;
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

/** Admin: season calendars, seasons, season hours and copy-year (user JWT). */
@RestController
@RequestMapping("/api")
public class SeasonController {
    private final SeasonService service;

    public SeasonController(SeasonService service) {
        this.service = service;
    }

    @GetMapping("/season-calendars")
    public List<SeasonDtos.CalendarRow> calendars() {
        return service.calendars();
    }

    @PostMapping("/season-calendars")
    public ResponseEntity<SeasonDtos.CalendarRow> createCalendar(@RequestBody SeasonDtos.CalendarInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createCalendar(input));
    }

    @PutMapping("/season-calendars/{id}")
    public SeasonDtos.CalendarRow renameCalendar(@PathVariable Long id, @RequestBody SeasonDtos.CalendarInput input) {
        return service.renameCalendar(id, input);
    }

    @DeleteMapping("/season-calendars/{id}")
    public ResponseEntity<Void> deleteCalendar(@PathVariable Long id) {
        service.deleteCalendar(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/season-calendars/{id}/seasons")
    public List<SeasonDtos.SeasonRow> seasons(@PathVariable Long id, @RequestParam(required = false) Integer year) {
        return service.seasons(id, year);
    }

    @PostMapping("/season-calendars/{id}/seasons")
    public ResponseEntity<SeasonDtos.SeasonRow> createSeason(@PathVariable Long id, @RequestBody SeasonDtos.SeasonInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSeason(id, input));
    }

    @PostMapping("/season-calendars/{id}/copy-year")
    public SeasonDtos.CopyYearResult copyYear(@PathVariable Long id, @RequestBody SeasonDtos.CopyYearRequest request) {
        return service.copyYear(id, request);
    }

    @PutMapping("/seasons/{id}")
    public SeasonDtos.SeasonRow updateSeason(@PathVariable Long id, @RequestBody SeasonDtos.SeasonInput input) {
        return service.updateSeason(id, input);
    }

    @DeleteMapping("/seasons/{id}")
    public ResponseEntity<Void> deleteSeason(@PathVariable Long id) {
        service.deleteSeason(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/seasons/{id}/operating")
    public List<SeasonDtos.Operating> operating(@PathVariable Long id) {
        return service.operating(id);
    }

    @PutMapping("/seasons/{id}/operating")
    public List<SeasonDtos.Operating> replaceOperating(@PathVariable Long id, @RequestBody List<SeasonDtos.Operating> rows) {
        return service.replaceOperating(id, rows);
    }
}
