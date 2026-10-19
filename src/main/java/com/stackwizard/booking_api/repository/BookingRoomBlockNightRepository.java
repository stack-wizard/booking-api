package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingRoomBlockNight;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface BookingRoomBlockNightRepository extends JpaRepository<BookingRoomBlockNight, Long> {
    List<BookingRoomBlockNight> findByTenantIdAndBlockIdOrderByNightAsc(Long tenantId, Long blockId);

    List<BookingRoomBlockNight> findByTenantIdAndBlockIdInOrderByNightAsc(Long tenantId, Collection<Long> blockIds);
}
