package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PaymentCardType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentCardTypeRepository extends JpaRepository<PaymentCardType, Long> {
    List<PaymentCardType> findByTenantIdOrderByCodeAscIdAsc(Long tenantId);

    Optional<PaymentCardType> findByTenantIdAndCodeIgnoreCase(Long tenantId, String code);

    Optional<PaymentCardType> findByTenantIdAndCodeIgnoreCaseAndActiveTrue(Long tenantId, String code);
}
