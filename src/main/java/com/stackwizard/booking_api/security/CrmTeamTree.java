package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.CrmTeamMember;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Team visibility: a lead sees the teams they lead and every sub-team; event coordinators also see the
 * teams they are members of. Only memberships active on the day count.
 */
public final class CrmTeamTree {

    private CrmTeamTree() {
    }

    public record Visibility(Set<Long> ledTeamIds, Set<Long> teamIds, Set<Long> userIds) {
        public boolean empty() {
            return teamIds.isEmpty();
        }
    }

    public static Visibility resolve(Long userId,
                                     boolean memberTeamsVisible,
                                     List<CrmTeamMember> activeMembers,
                                     Map<Long, Long> parentByTeamId) {
        Set<Long> led = new HashSet<>();
        Set<Long> member = new HashSet<>();
        for (CrmTeamMember m : activeMembers) {
            if (!m.getAppUserId().equals(userId)) {
                continue;
            }
            member.add(m.getTeamId());
            if (Boolean.TRUE.equals(m.getTeamLead())) {
                led.add(m.getTeamId());
            }
        }
        Set<Long> ledTree = withDescendants(led, parentByTeamId);
        Set<Long> teams = new HashSet<>(ledTree);
        if (memberTeamsVisible) {
            teams.addAll(withDescendants(member, parentByTeamId));
        }
        Set<Long> users = new HashSet<>();
        for (CrmTeamMember m : activeMembers) {
            if (teams.contains(m.getTeamId())) {
                users.add(m.getAppUserId());
            }
        }
        return new Visibility(Set.copyOf(ledTree), Set.copyOf(teams), Set.copyOf(users));
    }

    public static Set<Long> withDescendants(Collection<Long> roots, Map<Long, Long> parentByTeamId) {
        Map<Long, List<Long>> children = new HashMap<>();
        parentByTeamId.forEach((team, parent) -> {
            if (parent != null) {
                children.computeIfAbsent(parent, k -> new ArrayList<>()).add(team);
            }
        });
        Set<Long> out = new HashSet<>();
        Deque<Long> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            Long team = queue.poll();
            if (out.add(team)) {
                queue.addAll(children.getOrDefault(team, List.of()));
            }
        }
        return out;
    }
}
