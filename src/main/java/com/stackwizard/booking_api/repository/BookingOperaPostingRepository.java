package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingOperaPosting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingOperaPostingRepository extends JpaRepository<BookingOperaPosting, Long> {
    List<BookingOperaPosting> findByTenantIdAndBookingIdOrderByIdAsc(Long tenantId, Long bookingId);

    List<BookingOperaPosting> findByBookingOperaBlockIdAndStatus(Long bookingOperaBlockId, BookingOperaPosting.Status status);
}
