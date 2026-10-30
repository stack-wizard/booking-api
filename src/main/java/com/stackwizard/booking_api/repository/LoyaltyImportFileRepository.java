package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.LoyaltyImportFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LoyaltyImportFileRepository extends JpaRepository<LoyaltyImportFile, Long> {
    @Query("select length(f.content) from LoyaltyImportFile f where f.batchId = :batchId")
    Optional<Integer> contentLength(Long batchId);
}
