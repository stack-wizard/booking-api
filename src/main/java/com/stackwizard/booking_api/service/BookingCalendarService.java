package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.repository.BookingCalendarRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class BookingCalendarService {
    private final BookingCalendarRepository repo;

    public BookingCalendarService(BookingCalendarRepository repo) { this.repo = repo; }

    public List<BookingCalendar> findAll() { return repo.findAll(); }
    public Optional<BookingCalendar> findById(Long id) { return repo.findById(id); }
    public BookingCalendar save(BookingCalendar calendar) { return repo.save(calendar); }
    public void deleteById(Long id) { repo.deleteById(id); }
}
