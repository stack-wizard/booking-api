package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Capacity of a resource in a time window, counted in units.
 * <p>
 * EXACT resources hold {@code unit_count} units (normally 1). A POOL holds {@code pool_total_units}; its
 * usage is the units allocated on the pool itself plus the units allocated on EXACT resources whose
 * {@code parent_pool_id} is the pool, so "Office 3" and "any office" draw from the same stock. An EXACT
 * member is free only when it is free itself and its pool still has a unit.
 */
@Service
public class AllocationCapacityService {
    static final String NO_AVAILABILITY = "No availability for the requested resource in the given time range";

    private final AllocationRepository allocationRepo;

    public AllocationCapacityService(AllocationRepository allocationRepo) {
        this.allocationRepo = allocationRepo;
    }

    public void ensure(Resource resource, LocalDateTime from, LocalDateTime to, int units, Set<Long> excludeReservationIds) {
        int requested = Math.max(units, 1);
        int free = freeUnits(resource, from, to, excludeReservationIds);
        if (requested > free) {
            if (isPool(resource) && free > 0) {
                throw new IllegalStateException(NO_AVAILABILITY + ": " + free + " of " + capacityOf(resource)
                        + " units free in " + resource.getName() + ", " + requested + " requested");
            }
            throw new IllegalStateException(NO_AVAILABILITY);
        }
    }

    public int freeUnits(Resource resource, LocalDateTime from, LocalDateTime to, Set<Long> excludeReservationIds) {
        List<Allocation> usage = isPool(resource)
                ? allocationRepo.findActiveOnPoolOrMembers(resource.getId(), to, from)
                : allocationRepo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
                List.of(resource.getId()), to, from);
        int free = capacityOf(resource) - peakUnits(exclude(usage, excludeReservationIds), from, to);
        Resource pool = resource.getParentPool();
        if (!isPool(resource) && pool != null && isPool(pool)) {
            free = Math.min(free, freeUnits(pool, from, to, excludeReservationIds));
        }
        return Math.max(free, 0);
    }

    public static boolean isPool(Resource resource) {
        return resource != null && "POOL".equalsIgnoreCase(resource.getKind());
    }

    public static int capacityOf(Resource resource) {
        if (isPool(resource)) {
            if (resource.getPoolTotalUnits() == null) {
                throw new IllegalStateException("Pool resource missing poolTotalUnits");
            }
            return resource.getPoolTotalUnits();
        }
        return resource.getUnitCount() != null ? resource.getUnitCount() : 1;
    }

    public static int unitsOf(Allocation allocation) {
        return allocation.getUnits() != null && allocation.getUnits() > 0 ? allocation.getUnits() : 1;
    }

    /** Highest number of units held at the same moment inside {@code [from, to)}. */
    public static int peakUnits(Collection<Allocation> allocations, LocalDateTime from, LocalDateTime to) {
        int peak = 0;
        int load = 0;
        for (Step step : steps(allocations, from, to)) {
            load += step.delta();
            peak = Math.max(peak, load);
        }
        return peak;
    }

    /** Sub-windows of {@code [from, to)} where the held units reach {@code capacity}. */
    public static List<Window> saturated(Collection<Allocation> allocations, int capacity, LocalDateTime from, LocalDateTime to) {
        List<Window> out = new ArrayList<>();
        if (capacity <= 0) {
            out.add(new Window(from, to));
            return out;
        }
        int load = 0;
        LocalDateTime fullSince = null;
        List<Step> steps = steps(allocations, from, to);
        for (int i = 0; i < steps.size(); i++) {
            Step step = steps.get(i);
            load += step.delta();
            if (i + 1 < steps.size() && steps.get(i + 1).at().equals(step.at())) {
                continue;
            }
            if (load >= capacity && fullSince == null) {
                fullSince = step.at();
            } else if (load < capacity && fullSince != null) {
                if (step.at().isAfter(fullSince)) {
                    out.add(new Window(fullSince, step.at()));
                }
                fullSince = null;
            }
        }
        if (fullSince != null && to.isAfter(fullSince)) {
            out.add(new Window(fullSince, to));
        }
        return out;
    }

    private static List<Step> steps(Collection<Allocation> allocations, LocalDateTime from, LocalDateTime to) {
        List<Step> steps = new ArrayList<>();
        for (Allocation allocation : allocations) {
            LocalDateTime start = allocation.getStartsAt().isBefore(from) ? from : allocation.getStartsAt();
            LocalDateTime end = allocation.getEndsAt().isAfter(to) ? to : allocation.getEndsAt();
            if (!end.isAfter(start)) {
                continue;
            }
            int units = unitsOf(allocation);
            steps.add(new Step(start, units));
            steps.add(new Step(end, -units));
        }
        // Ranges are [start, end): a release at t happens before a hold that starts at t.
        steps.sort(Comparator.comparing(Step::at).thenComparingInt(Step::delta));
        return steps;
    }

    private static List<Allocation> exclude(List<Allocation> allocations, Set<Long> excludeReservationIds) {
        if (excludeReservationIds == null || excludeReservationIds.isEmpty()) {
            return allocations;
        }
        return allocations.stream()
                .filter(a -> a.getReservation() == null || !excludeReservationIds.contains(a.getReservation().getId()))
                .toList();
    }

    public record Window(LocalDateTime start, LocalDateTime end) {
    }

    private record Step(LocalDateTime at, int delta) {
    }
}
