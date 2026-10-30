package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyImportFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LoyaltyImportFileRepository extends JpaRepository<LoyaltyImportFile, Long> {
    /** Native: HQL has no length() for binary columns. */
    @Query(value = "select octet_length(content) from loyalty_import_file where batch_id = :batchId", nativeQuery = true)
    Optional<Long> contentLength(Long batchId);
}
