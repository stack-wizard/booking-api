package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyMember;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface LoyaltyMemberRepository extends JpaRepository<LoyaltyMember, Long> {
    Optional<LoyaltyMember> findByIdAndTenantId(Long id, Long tenantId);

    Optional<LoyaltyMember> findByProgramIdAndMemberNumber(Long programId, String memberNumber);

    Optional<LoyaltyMember> findByProgramIdAndContactId(Long programId, Long contactId);

    List<LoyaltyMember> findByContactIdOrderByIdAsc(Long contactId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from LoyaltyMember m where m.id = :id")
    Optional<LoyaltyMember> lockById(Long id);

    @Query(value = """
            select m.* from loyalty_member m
            join crm_contact c on c.id = m.contact_id
            where m.tenant_id = :tenantId and m.program_id = :programId
              and (:tierId is null or m.assigned_tier_id = :tierId)
              and (:number is null or m.member_number = :number)
              and (:email is null or lower(c.email) = lower(:email)
                   or exists (select 1 from crm_contact_identifier i
                              where i.contact_id = m.contact_id and i.type = 'EMAIL' and i.value_norm = lower(:email)))
              and (:q is null or lower(c.first_name || ' ' || c.last_name) like '%' || lower(:q) || '%'
                   or m.member_number = :q)
            order by m.id desc
            """,
            countQuery = """
            select count(*) from loyalty_member m
            join crm_contact c on c.id = m.contact_id
            where m.tenant_id = :tenantId and m.program_id = :programId
              and (:tierId is null or m.assigned_tier_id = :tierId)
              and (:number is null or m.member_number = :number)
              and (:email is null or lower(c.email) = lower(:email)
                   or exists (select 1 from crm_contact_identifier i
                              where i.contact_id = m.contact_id and i.type = 'EMAIL' and i.value_norm = lower(:email)))
              and (:q is null or lower(c.first_name || ' ' || c.last_name) like '%' || lower(:q) || '%'
                   or m.member_number = :q)
            """,
            nativeQuery = true)
    Page<LoyaltyMember> search(Long tenantId, Long programId, String q, String email, String number, Long tierId, Pageable pageable);

    @Query("""
            select m.id from LoyaltyMember m
            where m.status = com.stackwizard.booking_api.model.LoyaltyMember.Status.ACTIVE
              and m.tierValidUntil is not null and m.tierValidUntil < :today
              and (:programId is null or m.programId = :programId)
            """)
    List<Long> findIdsWithExpiredTier(LocalDate today, Long programId, Pageable pageable);

    @Query("""
            select m.id from LoyaltyMember m
            where m.status = com.stackwizard.booking_api.model.LoyaltyMember.Status.ACTIVE
              and (m.countersYear is null or m.countersYear < :year)
              and (:programId is null or m.programId = :programId)
            """)
    List<Long> findIdsWithStaleCounters(int year, Long programId, Pageable pageable);

    @Query("""
            select m from LoyaltyMember m
            where m.status = com.stackwizard.booking_api.model.LoyaltyMember.Status.ACTIVE
              and m.tierValidUntil in :dates
              and (:programId is null or m.programId = :programId)
            """)
    List<LoyaltyMember> findExpiringOn(List<LocalDate> dates, Long programId);

    @Query("select max(m.memberNumber) from LoyaltyMember m where m.programId = :programId")
    Optional<String> maxMemberNumber(Long programId);
}
