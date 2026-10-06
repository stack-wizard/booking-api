package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.CrmTeamMember;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CrmTeamTreeTest {

    // 1 Sales  ─┬─ 2 MICE ── 4 MICE DACH
    //           └─ 3 Leisure
    private static final Map<Long, Long> PARENTS = new HashMap<>();

    static {
        PARENTS.put(1L, null);
        PARENTS.put(2L, 1L);
        PARENTS.put(3L, 1L);
        PARENTS.put(4L, 2L);
    }

    private static CrmTeamMember member(long team, long user, boolean lead) {
        return CrmTeamMember.builder().teamId(team).appUserId(user).teamLead(lead).build();
    }

    @Test
    void leadSeesLedTeamAndSubTeams() {
        List<CrmTeamMember> active = List.of(
                member(2, 100, true), member(2, 101, false), member(4, 102, false), member(3, 103, false));

        CrmTeamTree.Visibility v = CrmTeamTree.resolve(100L, false, active, PARENTS);

        assertThat(v.ledTeamIds()).containsExactlyInAnyOrder(2L, 4L);
        assertThat(v.teamIds()).containsExactlyInAnyOrder(2L, 4L);
        assertThat(v.userIds()).containsExactlyInAnyOrder(100L, 101L, 102L);
    }

    @Test
    void plainRepSeesNoTeam() {
        List<CrmTeamMember> active = List.of(member(2, 100, true), member(2, 101, false));

        CrmTeamTree.Visibility v = CrmTeamTree.resolve(101L, false, active, PARENTS);

        assertThat(v.empty()).isTrue();
        assertThat(v.userIds()).isEmpty();
    }

    @Test
    void coordinatorSeesMemberTeams() {
        List<CrmTeamMember> active = List.of(member(3, 200, false), member(3, 103, false), member(2, 101, false));

        CrmTeamTree.Visibility v = CrmTeamTree.resolve(200L, true, active, PARENTS);

        assertThat(v.ledTeamIds()).isEmpty();
        assertThat(v.teamIds()).containsExactly(3L);
        assertThat(v.userIds()).containsExactlyInAnyOrder(200L, 103L);
    }

    @Test
    void withDescendantsSurvivesCycles() {
        Map<Long, Long> cyclic = Map.of(1L, 2L, 2L, 1L);
        assertThat(CrmTeamTree.withDescendants(Set.of(1L), cyclic)).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void ownerScopeAllowsTeamRecordsWithoutTeamOwner() {
        CrmOwnerScope scope = new CrmOwnerScope(false, false, true, 100L, Set.of(100L, 101L), Set.of(2L));

        assertThat(scope.allows(101L, null)).isTrue();
        assertThat(scope.allows(null, 2L)).isTrue();
        assertThat(scope.allows(999L, 3L)).isFalse();
        assertThat(scope.allows(null, null)).isFalse();
    }

    @Test
    void ownScopeOnlyAllowsOwnRecords() {
        CrmOwnerScope scope = new CrmOwnerScope(false, true, false, 101L, Set.of(-1L), Set.of(-1L));

        assertThat(scope.allows(101L, 2L)).isTrue();
        assertThat(scope.allows(100L, 2L)).isFalse();
    }
}
