package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.BookingStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BookingStatusRepository extends JpaRepository<BookingStatus, Long> {
    List<BookingStatus> findByTenantIdOrderByDisplayOrderAscIdAsc(Long tenantId);

    Optional<BookingStatus> findByIdAndTenantId(Long id, Long tenantId);

    Optional<BookingStatus> findByTenantIdAndCode(Long tenantId, String code);

    boolean existsByTenantId(Long tenantId);
}
