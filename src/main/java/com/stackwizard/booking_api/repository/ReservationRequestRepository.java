package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.ReservationRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

public interface ReservationRequestRepository extends JpaRepository<ReservationRequest, Long>, JpaSpecificationExecutor<ReservationRequest> {
    Optional<ReservationRequest> findByConfirmationCodeAndTenantId(String confirmationCode, Long tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ReservationRequest> findLockedByStatusInAndExpiresAtBefore(List<ReservationRequest.Status> statuses, OffsetDateTime threshold);
}
