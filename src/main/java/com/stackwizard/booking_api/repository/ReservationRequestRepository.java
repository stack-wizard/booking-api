package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ReservationRequestRepository extends JpaRepository<ReservationRequest, Long>, JpaSpecificationExecutor<ReservationRequest> {
    Optional<ReservationRequest> findByConfirmationCodeAndTenantId(String confirmationCode, Long tenantId);
}
