package com.stackwizard.booking_api.repository;

import com.stackwizard.booking_api.model.EventDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface EventDeliveryRepository extends JpaRepository<EventDelivery, Long> {
    List<EventDelivery> findByEventIdOrderByIdAsc(Long eventId);

    /** Claims due deliveries across instances (skip locked + lease); a crashed sender's lease simply expires. */
    @Query(value = """
            update event_delivery set lease_until = now() + interval '2 minutes'
            where id in (select id from event_delivery
                         where status = 'PENDING' and next_attempt_at <= now()
                           and (lease_until is null or lease_until < now())
                         order by next_attempt_at limit :limit for update skip locked)
            returning id
            """, nativeQuery = true)
    List<Long> claimDue(int limit);
}
