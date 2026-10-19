package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.OperaCodeMap;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OperaCodeMapRepository extends JpaRepository<OperaCodeMap, Long> {
    List<OperaCodeMap> findByTenantIdAndOperaHotelIdOrderByKindAscLocalRefAsc(Long tenantId, Long operaHotelId);

    Optional<OperaCodeMap> findByTenantIdAndOperaHotelIdAndKindAndLocalRef(Long tenantId, Long operaHotelId,
                                                                          OperaCodeMap.Kind kind, String localRef);

    Optional<OperaCodeMap> findByTenantIdAndOperaHotelIdAndKindAndOperaCode(Long tenantId, Long operaHotelId,
                                                                           OperaCodeMap.Kind kind, String operaCode);
}
