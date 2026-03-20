package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaHotel;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaHotelRepository extends JpaRepository<OperaHotel, Long> {
    List<OperaHotel> findByTenantIdOrderByHotelCodeAscIdAsc(Long tenantId);

    Optional<OperaHotel> findByTenantIdAndHotelCodeIgnoreCase(Long tenantId, String hotelCode);

    Optional<OperaHotel> findByTenantIdAndHotelCodeIgnoreCaseAndActiveTrue(Long tenantId, String hotelCode);
}
