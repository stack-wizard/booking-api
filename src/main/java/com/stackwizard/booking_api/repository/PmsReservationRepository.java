package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.PmsReservation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PmsReservationRepository extends JpaRepository<PmsReservation, Long> {
    Optional<PmsReservation> findByIdAndTenantId(Long id, Long tenantId);

    Optional<PmsReservation> findByPropertyTenantIdAndPmsSystemAndPmsReservationId(
            Long propertyTenantId, PmsReservation.PmsSystem pmsSystem, String pmsReservationId);

    List<PmsReservation> findByContactIdOrderByArrivalDesc(Long contactId);

    @Query("""
            select r from PmsReservation r
            where r.tenantId = :tenantId
              and (:status is null or r.status = :status)
              and (:propertyTenantId is null or r.propertyTenantId = :propertyTenantId)
              and (:contactId is null or r.contactId = :contactId)
              and (:unmatched = false or r.contactId is null)
              and (:q is null or r.confirmationNo = :q or r.pmsReservationId = :q
                   or lower(r.holderLastName) like lower(concat('%', :q, '%')) or r.holderEmailNorm = lower(:q))
            order by r.arrival desc, r.id desc
            """)
    Page<PmsReservation> search(Long tenantId, PmsReservation.Status status, Long propertyTenantId, Long contactId,
                                boolean unmatched, String q, Pageable pageable);

    @Query("""
            select r from PmsReservation r
            where r.contactId = :contactId
              and r.status in (com.stackwizard.booking_api.model.PmsReservation.Status.CHECKED_OUT,
                               com.stackwizard.booking_api.model.PmsReservation.Status.IN_HOUSE,
                               com.stackwizard.booking_api.model.PmsReservation.Status.RESERVED)
            """)
    List<PmsReservation> findForStats(Long contactId);

    @Query("""
            select r from PmsReservation r
            where r.tenantId = :tenantId and r.status = com.stackwizard.booking_api.model.PmsReservation.Status.CHECKED_OUT
              and (r.companyPmsProfileId = :pmsProfileId or r.agentPmsProfileId = :pmsProfileId or r.groupPmsProfileId = :pmsProfileId)
            """)
    List<PmsReservation> findCheckedOutForAccount(Long tenantId, String pmsProfileId);
}
