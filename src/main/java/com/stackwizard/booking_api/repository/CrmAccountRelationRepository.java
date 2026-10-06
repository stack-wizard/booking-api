package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAccountRelation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface CrmAccountRelationRepository extends JpaRepository<CrmAccountRelation, Long> {
    Optional<CrmAccountRelation> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select r from CrmAccountRelation r
            where r.tenantId = :tenantId and (r.fromAccountId = :accountId or r.toAccountId = :accountId)
            order by r.relationType, r.id
            """)
    List<CrmAccountRelation> findForAccount(@Param("tenantId") Long tenantId, @Param("accountId") Long accountId);

    boolean existsByTenantIdAndFromAccountIdAndToAccountIdAndRelationType(Long tenantId, Long fromAccountId, Long toAccountId,
                                                                         CrmAccountRelation.Type relationType);
}
