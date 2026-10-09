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
