package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    Optional<AppUser> findByPlatformUserId(UUID platformUserId);

    boolean existsByUsername(String username);

    List<AppUser> findByTenantIdOrderByUsernameAsc(Long tenantId);

    /**
     * Platform users of the organization and of every hotel under it. People may still sit on a hotel
     * {@code tenant_id} until their next login moves them to the chain.
     */
    @Query("""
            select u from AppUser u
            where u.platformUserId is not null
              and (u.username is null or u.username not like 'online-system-tenant-%')
              and (u.tenantId = :orgTenantId
                   or u.tenantId in (
                       select m.tenantId from PlatformTenantMapping m where m.parentTenantId = :orgTenantId))
            """)
    List<AppUser> findPlatformUsersOfChain(@Param("orgTenantId") Long orgTenantId);

    Optional<AppUser> findByIdAndTenantId(Long id, Long tenantId);

    Optional<AppUser> findByTenantIdAndUsername(Long tenantId, String username);

    /**
     * A user of {@code tenantId} (hotel) or of the organization that owns it. People belong to the chain,
     * while system users such as the online issuer stay with their hotel.
     */
    @Query("""
            select u from AppUser u
            where u.id = :id
              and (u.tenantId = :tenantId
                   or u.tenantId = (select m.parentTenantId from PlatformTenantMapping m where m.tenantId = :tenantId))
            """)
    Optional<AppUser> findByIdInTenantOrItsOrg(@Param("id") Long id, @Param("tenantId") Long tenantId);
}
