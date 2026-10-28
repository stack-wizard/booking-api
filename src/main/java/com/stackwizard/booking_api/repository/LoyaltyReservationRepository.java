package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyReservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoyaltyReservationRepository extends JpaRepository<LoyaltyReservation, Long> {
    Optional<LoyaltyReservation> findByIdAndTenantId(Long id, Long tenantId);

    Optional<LoyaltyReservation> findByPropertyTenantIdAndExternalSystemAndConfirmationNoAndExternalSubId(
            Long propertyTenantId, LoyaltyReservation.ExternalSystem externalSystem, String confirmationNo, String externalSubId);

    List<LoyaltyReservation> findByMemberIdOrderByArrivalDesc(Long memberId);

    @Query("""
            select r from LoyaltyReservation r
            where r.tenantId = :tenantId
              and (:status is null or r.status = :status)
              and (:propertyTenantId is null or r.propertyTenantId = :propertyTenantId)
              and (:unmatched = false or r.memberId is null)
            order by r.arrival desc, r.id desc
            """)
    Page<LoyaltyReservation> search(Long tenantId, LoyaltyReservation.Status status, Long propertyTenantId, boolean unmatched, Pageable pageable);
}
