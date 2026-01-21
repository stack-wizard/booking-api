package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;

import java.util.List;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {
	long countByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(Long resourceId, LocalDateTime endsAt, LocalDateTime startsAt);

	List<Allocation> findByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(Long resourceId, LocalDateTime endsAt, LocalDateTime startsAt);

	List<Allocation> findByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(List<Long> resourceIds, LocalDateTime endsAt, LocalDateTime startsAt);
}
