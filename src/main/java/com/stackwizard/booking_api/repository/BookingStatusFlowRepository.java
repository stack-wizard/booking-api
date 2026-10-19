package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingStatusFlow;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BookingStatusFlowRepository extends JpaRepository<BookingStatusFlow, Long> {
    List<BookingStatusFlow> findByTenantId(Long tenantId);

    List<BookingStatusFlow> findByTenantIdAndFromStatusId(Long tenantId, Long fromStatusId);
}
