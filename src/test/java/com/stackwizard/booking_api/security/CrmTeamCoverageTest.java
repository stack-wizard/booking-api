package com.stackwizard.booking_api.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrmTeamCoverageTest {
    // 1 = chain-wide team, 2 = hotels 10+11, 3 = hotel 12 only, 4 = sub-team of 2 without own hotels
    private final Map<Long, Long> parents = new java.util.HashMap<>() {{
        put(1L, null);
        put(2L, null);
        put(3L, null);
        put(4L, 2L);
    }};
    private final Map<Long, Set<Long>> hotels = Map.of(2L, Set.of(10L, 11L), 3L, Set.of(12L));

    @Test
    void teamWithoutHotelsCoversTheWholeChain() {
        CrmTeamCoverage.Coverage coverage = CrmTeamCoverage.effective(1L, parents, hotels);
        assertThat(coverage.allHotels()).isTrue();
        assertThat(coverage.covers(99L)).isTrue();
    }

    @Test
    void teamCoversOnlyItsHotels() {
        CrmTeamCoverage.Coverage coverage = CrmTeamCoverage.effective(2L, parents, hotels);
        assertThat(coverage.covers(10L)).isTrue();
        assertThat(coverage.covers(12L)).isFalse();
        assertThat(coverage.covers(null)).isFalse();
    }

    @Test
    void subTeamInheritsTheParentsHotels() {
        assertThat(CrmTeamCoverage.effective(4L, parents, hotels).propertyIds()).containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    void unionOfTeamsMergesHotelsAndOneChainWideTeamWins() {
        CrmTeamCoverage.Coverage two = CrmTeamCoverage.union(List.of(2L, 3L), parents, hotels);
        assertThat(two.allHotels()).isFalse();
        assertThat(two.propertyIds()).containsExactlyInAnyOrder(10L, 11L, 12L);
        assertThat(CrmTeamCoverage.union(List.of(2L, 1L), parents, hotels).allHotels()).isTrue();
    }

    @Test
    void ownerSeesOwnRecordsButTeamIsNarrowedByHotel() {
        CrmOwnerScope scope = new CrmOwnerScope(false, false, true, 100L, Set.of(100L, 101L), Set.of(2L),
                false, Set.of(10L));
        assertThat(scope.allows(100L, 2L, 99L)).isTrue();   // own record, other hotel
        assertThat(scope.allows(101L, 2L, 10L)).isTrue();   // teammate, covered hotel
        assertThat(scope.allows(101L, 2L, 12L)).isFalse();  // teammate, other hotel
        assertThat(scope.allows(101L, 2L, null)).isFalse(); // teammate, chain-wide record
        CrmOwnerScope all = new CrmOwnerScope(false, false, true, 100L, Set.of(100L, 101L), Set.of(2L),
                true, Set.of(-1L));
        assertThat(all.allows(101L, 2L, null)).isTrue();
    }
}
