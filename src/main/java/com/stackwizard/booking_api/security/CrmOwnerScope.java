package com.stackwizard.booking_api.security;

import java.util.Collection;
import java.util.Set;

/**
 * Row visibility: OWN sees records it owns; TEAM also sees records owned by members of visible teams or
 * carrying one of those teams; ALL sees everything. Collections are never empty so JPQL {@code in} stays valid.
 */
public record CrmOwnerScope(boolean all,
                            boolean own,
                            boolean team,
                            Long currentUserId,
                            Collection<Long> teamUserIds,
                            Collection<Long> teamIds) {

    private static final Set<Long> NONE = Set.of(-1L);

    public static CrmOwnerScope from(CrmAccessContext ctx) {
        CrmScope scope = ctx.scope();
        return new CrmOwnerScope(
                scope == CrmScope.ALL,
                scope == CrmScope.OWN,
                scope == CrmScope.TEAM,
                ctx.currentUserId(),
                nonEmpty(scope == CrmScope.ALL ? null : ctx.teamUserIds()),
                nonEmpty(scope == CrmScope.TEAM ? ctx.teamIds() : null)
        );
    }

    public boolean allows(Long ownerUserId) {
        return allows(ownerUserId, null);
    }

    public boolean allows(Long ownerUserId, Long teamId) {
        if (all) {
            return true;
        }
        if (ownerUserId != null && ownerUserId.equals(currentUserId)) {
            return true;
        }
        if (team) {
            return (ownerUserId != null && teamUserIds.contains(ownerUserId))
                    || (teamId != null && teamIds.contains(teamId));
        }
        return false;
    }

    private static Collection<Long> nonEmpty(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? NONE : ids;
    }
}
