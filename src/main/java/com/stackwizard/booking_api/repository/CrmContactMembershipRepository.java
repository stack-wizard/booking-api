package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmContactMembership;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmContactMembershipRepository extends JpaRepository<CrmContactMembership, Long> {
    List<CrmContactMembership> findByContactIdOrderByIdAsc(Long contactId);

    Optional<CrmContactMembership> findByTenantIdAndProgramCodeIgnoreCaseAndCardNo(Long tenantId, String programCode, String cardNo);

    Optional<CrmContactMembership> findFirstByTenantIdAndCardNo(Long tenantId, String cardNo);
}
