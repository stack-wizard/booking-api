package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyImportBatch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface LoyaltyImportBatchRepository extends JpaRepository<LoyaltyImportBatch, Long> {
    Optional<LoyaltyImportBatch> findByIdAndTenantId(Long id, Long tenantId);

    List<LoyaltyImportBatch> findTop50ByTenantIdOrderByIdDesc(Long tenantId);

    /**
     * One instance wins a batch: queued work, or in-flight work whose lease expired (crashed instance).
     */
    @Query(value = """
            update loyalty_import_batch set lease_owner = :owner, lease_until = now() + interval '2 minutes', updated_at = now()
            where id = (select id from loyalty_import_batch
                        where status in ('QUEUED','STAGING','APPLY_QUEUED','APPLYING')
                          and (lease_until is null or lease_until < now())
                        order by id limit 1 for update skip locked)
            returning id
            """, nativeQuery = true)
    List<Long> claimNext(String owner);

    @Modifying
    @Query(value = """
            update loyalty_import_batch set lease_until = now() + interval '2 minutes'
            where id = :id and lease_owner = :owner
            """, nativeQuery = true)
    int heartbeat(Long id, String owner);
}
