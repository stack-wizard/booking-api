package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.CrmContactMergeLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CrmContactMergeLogRepository extends JpaRepository<CrmContactMergeLog, Long> {
    List<CrmContactMergeLog> findByIntoContactIdOrderByIdDesc(Long intoContactId);
}
