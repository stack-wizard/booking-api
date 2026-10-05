package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAccount;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.Optional;

public interface CrmAccountRepository extends JpaRepository<CrmAccount, Long> {
    Optional<CrmAccount> findByIdAndTenantId(Long id, Long tenantId);

    @Query("""
            select a from CrmAccount a
            where a.tenantId = :tenantId
              and (:active is null or a.active = :active)
              and (:accountType is null or a.accountType = :accountType)
              and (:segment is null or a.segment = :segment)
              and (:ownerUserId is null or a.ownerUserId = :ownerUserId)
              and (:search is null or lower(a.name) like lower(concat('%', cast(:search as string), '%'))
                   or lower(coalesce(a.vatId, '')) like lower(concat('%', cast(:search as string), '%')))
              and (
                   :scopeAll = true
                   or (:scopeOwn = true and a.ownerUserId = :currentUserId)
                   or (:scopeTeam = true and a.ownerUserId in :teamUserIds)
              )
            """)
    Page<CrmAccount> search(@Param("tenantId") Long tenantId,
                            @Param("search") String search,
                            @Param("accountType") CrmAccount.AccountType accountType,
                            @Param("segment") String segment,
                            @Param("ownerUserId") Long ownerUserId,
                            @Param("active") Boolean active,
                            @Param("scopeAll") boolean scopeAll,
                            @Param("scopeOwn") boolean scopeOwn,
                            @Param("scopeTeam") boolean scopeTeam,
                            @Param("currentUserId") Long currentUserId,
                            @Param("teamUserIds") Collection<Long> teamUserIds,
                            Pageable pageable);
}
