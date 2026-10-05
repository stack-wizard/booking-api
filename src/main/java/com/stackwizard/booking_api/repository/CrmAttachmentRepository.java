package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CrmAttachmentRepository extends JpaRepository<CrmAttachment, Long> {
    List<CrmAttachment> findByTenantIdAndAccountIdOrderByCreatedAtDesc(Long tenantId, Long accountId);
    Optional<CrmAttachment> findByIdAndTenantId(Long id, Long tenantId);
}
