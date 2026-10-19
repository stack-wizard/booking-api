package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaCodeRepository extends JpaRepository<OperaCode, Long> {
    List<OperaCode> findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(Long tenantId, Long operaHotelId, OperaCode.Kind kind);

    Optional<OperaCode> findByTenantIdAndOperaHotelIdAndKindAndCode(Long tenantId, Long operaHotelId, OperaCode.Kind kind, String code);
}
