package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AllocationCapacityServiceTest {
    private static final LocalDateTime TEN = LocalDateTime.of(2027, 3, 20, 10, 0);
    private static final LocalDateTime TWELVE = TEN.plusHours(2);

    private final List<Allocation> store = new ArrayList<>();
    private AllocationCapacityService service;
    private long nextReservationId = 1;

    @BeforeEach
    void setUp() {
        AllocationRepository repo = mock(AllocationRepository.class);
        when(repo.findActiveOnPoolOrMembers(anyLong(), any(), any())).thenAnswer(inv -> {
            Long poolId = inv.getArgument(0);
            return active(inv.getArgument(2), inv.getArgument(1)).stream()
                    .filter(a -> poolId.equals(a.getAllocatedResource().getId())
                            || (a.getAllocatedResource().getParentPool() != null
                            && poolId.equals(a.getAllocatedResource().getParentPool().getId())))
                    .toList();
        });
        when(repo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(anyList(), any(), any()))
                .thenAnswer(inv -> {
                    List<Long> ids = inv.getArgument(0);
                    return active(inv.getArgument(2), inv.getArgument(1)).stream()
                            .filter(a -> ids.contains(a.getAllocatedResource().getId()))
                            .toList();
                });
        service = new AllocationCapacityService(repo);
    }

    @Test
    void poolCountsUnitsNotRows() {
        Resource rooms = pool(10L, 10);

        hold(rooms, TEN, TWELVE, 7);

        assertThat(service.freeUnits(rooms, TEN, TWELVE, Set.of())).isEqualTo(3);
        assertThatThrownBy(() -> service.ensure(rooms, TEN, TWELVE, 4, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("3 of 10 units free");
        service.ensure(rooms, TEN, TWELVE, 3, Set.of());
    }

    @Test
    void expiredHoldReleasesItsUnits() {
        Resource rooms = pool(10L, 10);
        Allocation stale = hold(rooms, TEN, TWELVE, 7);
        stale.setExpiresAt(OffsetDateTime.now().minusMinutes(1));

        service.ensure(rooms, TEN, TWELVE, 10, Set.of());
    }

    @Test
    void exactMemberAndAnyMemberShareThePool() {
        Resource offices = pool(10L, 5);
        Resource office3 = member(13L, offices);
        Resource office4 = member(14L, offices);

        hold(office3, TEN, TWELVE, 1);
        service.ensure(offices, TEN, TWELVE, 4, Set.of());
        hold(offices, TEN, TWELVE, 4);

        assertThat(service.freeUnits(offices, TEN, TWELVE, Set.of())).isZero();
        assertThatThrownBy(() -> service.ensure(offices, TEN, TWELVE, 1, Set.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.ensure(office4, TEN, TWELVE, 1, Set.of()))
                .as("Office 4 is untouched but the pool has no unit left")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void exactMemberCannotBeTakenTwice() {
        Resource offices = pool(10L, 5);
        Resource office3 = member(13L, offices);

        hold(office3, TEN, TWELVE, 1);

        assertThatThrownBy(() -> service.ensure(office3, TEN, TWELVE, 1, Set.of()))
                .isInstanceOf(IllegalStateException.class);
        service.ensure(office3, TWELVE, TWELVE.plusHours(1), 1, Set.of());
    }

    @Test
    void exactWithoutPoolKeepsOneBookingAtATime() {
        Resource hall = exact(20L, null);

        Allocation booked = hold(hall, TEN, TWELVE, 1);

        assertThatThrownBy(() -> service.ensure(hall, TEN.plusHours(1), TWELVE.plusHours(1), 1, Set.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage(AllocationCapacityService.NO_AVAILABILITY);
        service.ensure(hall, TEN.plusHours(1), TWELVE.plusHours(1), 1, Set.of(booked.getReservation().getId()));
    }

    @Test
    void consecutiveHoldsDoNotAddUp() {
        Resource rooms = pool(10L, 10);
        hold(rooms, TEN, TEN.plusHours(1), 6);
        hold(rooms, TEN.plusHours(1), TWELVE, 6);

        assertThat(service.freeUnits(rooms, TEN, TWELVE, Set.of())).isEqualTo(4);
        assertThat(AllocationCapacityService.saturated(store, 6, TEN, TWELVE))
                .containsExactly(new AllocationCapacityService.Window(TEN, TWELVE));
    }

    private List<Allocation> active(LocalDateTime from, LocalDateTime to) {
        OffsetDateTime now = OffsetDateTime.now();
        return store.stream()
                .filter(a -> a.getStartsAt().isBefore(to) && a.getEndsAt().isAfter(from))
                .filter(a -> "CONFIRMED".equals(a.getStatus())
                        || ("HOLD".equals(a.getStatus()) && (a.getExpiresAt() == null || a.getExpiresAt().isAfter(now))))
                .toList();
    }

    private Allocation hold(Resource resource, LocalDateTime from, LocalDateTime to, int units) {
        Reservation reservation = Reservation.builder().id(nextReservationId++).units(units).build();
        Allocation allocation = Allocation.builder()
                .reservation(reservation)
                .requestedResource(resource)
                .allocatedResource(resource)
                .resourceKind(resource.getKind())
                .startsAt(from)
                .endsAt(to)
                .units(units)
                .status("HOLD")
                .build();
        store.add(allocation);
        return allocation;
    }

    private static Resource pool(Long id, int total) {
        return Resource.builder().id(id).name("Pool " + id).kind("POOL").poolTotalUnits(total).unitCount(1).build();
    }

    private static Resource member(Long id, Resource pool) {
        return exact(id, Objects.requireNonNull(pool));
    }

    private static Resource exact(Long id, Resource pool) {
        return Resource.builder().id(id).name("Resource " + id).kind("EXACT").unitCount(1).parentPool(pool).build();
    }
}
