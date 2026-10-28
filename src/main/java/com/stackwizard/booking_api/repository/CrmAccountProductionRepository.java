package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmAccountProduction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrmAccountProductionRepository extends JpaRepository<CrmAccountProduction, Long> {
    List<CrmAccountProduction> findByAccountIdOrderByYearDescPropertyTenantIdAsc(Long accountId);

    void deleteByAccountId(Long accountId);
}
