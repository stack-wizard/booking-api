package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyImportRow;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface LoyaltyImportRowRepository extends JpaRepository<LoyaltyImportRow, Long> {
    @Query("select r from LoyaltyImportRow r where r.batchId = :batchId and r.rowNo > :afterRow order by r.rowNo asc")
    List<LoyaltyImportRow> findChunk(Long batchId, int afterRow, Pageable pageable);

    List<LoyaltyImportRow> findByBatchIdAndStatusInOrderByRowNoAsc(Long batchId, List<LoyaltyImportRow.Status> statuses);

    long countByBatchIdAndStatus(Long batchId, LoyaltyImportRow.Status status);

    @Modifying
    @Query("delete from LoyaltyImportRow r where r.batchId = :batchId and r.rowNo > :afterRow")
    int deleteAfter(Long batchId, int afterRow);

    @Query("select coalesce(max(r.rowNo), 0) from LoyaltyImportRow r where r.batchId = :batchId")
    int maxRowNo(Long batchId);
}
