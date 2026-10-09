package com.stackwizard.booking_api.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which hotels a team works for. A team without hotels covers the whole chain; a sub-team without hotels
 * inherits the coverage of its parent.
 */
public final class CrmTeamCoverage {

    private CrmTeamCoverage() {
    }

    /** {@code allHotels} = whole chain (then {@code propertyIds} is empty). */
    public record Coverage(boolean allHotels, Set<Long> propertyIds) {
        public static final Coverage ALL = new Coverage(true, Set.of());

        public boolean covers(Long propertyTenantId) {
            return allHotels || (propertyTenantId != null && propertyIds.contains(propertyTenantId));
        }
    }

    public static Coverage effective(Long teamId, Map<Long, Long> parentByTeamId, Map<Long, Set<Long>> hotelsByTeamId) {
        Set<Long> seen = new HashSet<>();
        Long current = teamId;
        while (current != null && seen.add(current)) {
            Set<Long> own = hotelsByTeamId.get(current);
            if (own != null && !own.isEmpty()) {
                return new Coverage(false, Set.copyOf(own));
            }
            current = parentByTeamId.get(current);
        }
        return Coverage.ALL;
    }

    /** Union over several teams: the whole chain as soon as one of them covers it. */
    public static Coverage union(Collection<Long> teamIds, Map<Long, Long> parentByTeamId, Map<Long, Set<Long>> hotelsByTeamId) {
        Set<Long> hotels = new HashSet<>();
        for (Long teamId : teamIds) {
            Coverage coverage = effective(teamId, parentByTeamId, hotelsByTeamId);
            if (coverage.allHotels()) {
                return Coverage.ALL;
            }
            hotels.addAll(coverage.propertyIds());
        }
        return new Coverage(false, Set.copyOf(hotels));
    }
}
