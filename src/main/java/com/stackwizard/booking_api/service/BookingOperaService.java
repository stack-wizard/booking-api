package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.OperaDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingOperaPosting;
import com.stackwizard.booking_api.service.opera.OperaPmPostingService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** OPERA side of one booking: its hotel blocks, a manual re-sync and posting catering / rental to the block PMs. */
@Service
public class BookingOperaService {
    private final BookingService bookingService;
    private final BookingReservationSync reservationSync;
    private final OperaPmPostingService postingService;

    public BookingOperaService(BookingService bookingService,
                               BookingReservationSync reservationSync,
                               OperaPmPostingService postingService) {
        this.bookingService = bookingService;
        this.reservationSync = reservationSync;
        this.postingService = postingService;
    }

    public OperaDtos.BookingOpera view(Long bookingId) {
        return postingService.view(bookingService.requireBooking(bookingId));
    }

    @Transactional
    public OperaDtos.BookingOpera sync(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        reservationSync.pushOperaBlock(booking);
        return postingService.view(booking);
    }

    @Transactional
    public List<BookingOperaPosting> postCharges(Long bookingId) {
        Booking booking = bookingService.requireBooking(bookingId);
        if (booking.getStatus().isClosed() && booking.getStatus() != Booking.Status.ACTUAL) {
            throw new IllegalStateException("Booking is " + booking.getStatus() + "; nothing is posted to OPERA");
        }
        return postingService.post(booking);
    }
}
