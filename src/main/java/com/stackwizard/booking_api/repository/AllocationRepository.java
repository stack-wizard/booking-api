package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDateTime;

import java.util.List;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {
	long countByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(Long resourceId, LocalDateTime endsAt, LocalDateTime startsAt);

	List<Allocation> findByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(Long resourceId, LocalDateTime endsAt, LocalDateTime startsAt);

	List<Allocation> findByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(List<Long> resourceIds, LocalDateTime endsAt, LocalDateTime startsAt);

	long deleteByReservationId(Long reservationId);
	long deleteByReservationIdIn(List<Long> reservationIds);

	List<Allocation> findByReservationIdIn(List<Long> reservationIds);

	@Query("""
			select count(a) from Allocation a
			where a.allocatedResource.id = :resourceId
			  and a.startsAt < :endsAt
			  and a.endsAt > :startsAt
			  and (
			    upper(a.status) = 'CONFIRMED'
			    or (upper(a.status) = 'HOLD' and (a.expiresAt is null or a.expiresAt > current_timestamp))
			  )
			""")
	long countActiveByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(
			@Param("resourceId") Long resourceId,
			@Param("endsAt") LocalDateTime endsAt,
			@Param("startsAt") LocalDateTime startsAt);

	@Query("""
			select a from Allocation a
			where a.allocatedResource.id in :resourceIds
			  and a.startsAt < :endsAt
			  and a.endsAt > :startsAt
			  and (
			    upper(a.status) = 'CONFIRMED'
			    or (upper(a.status) = 'HOLD' and (a.expiresAt is null or a.expiresAt > current_timestamp))
			  )
			""")
	List<Allocation> findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
			@Param("resourceIds") List<Long> resourceIds,
			@Param("endsAt") LocalDateTime endsAt,
			@Param("startsAt") LocalDateTime startsAt);
}
