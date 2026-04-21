package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findByTenantIdOrderByUsernameAsc(Long tenantId);

    Optional<AppUser> findByIdAndTenantId(Long id, Long tenantId);

    Optional<AppUser> findByTenantIdAndUsername(Long tenantId, String username);
}
