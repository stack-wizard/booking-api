package com.stackwizard.booking_api.security;

import java.util.Collection;
import java.util.Set;

/**
 * Row visibility: OWN sees records it owns; TEAM also sees records owned by members of visible teams or
 * carrying one of those teams; ALL sees everything. Opportunities and events are narrowed by hotel for TEAM. Collections are never empty so JPQL {@code in} stays valid.
 */
public record CrmOwnerScope(boolean all,
                            boolean own,
                            boolean team,
                            Long currentUserId,
                            Collection<Long> teamUserIds,
                            Collection<Long> teamIds,
                            boolean allHotels,
                            Collection<Long> propertyIds) {

    private static final Set<Long> NONE = Set.of(-1L);

    /** Scope that does not narrow by hotel (leads, accounts and tests). */
    public CrmOwnerScope(boolean all, boolean own, boolean team, Long currentUserId,
                         Collection<Long> teamUserIds, Collection<Long> teamIds) {
        this(all, own, team, currentUserId, teamUserIds, teamIds, true, NONE);
    }

    public static CrmOwnerScope from(CrmAccessContext ctx) {
        CrmScope scope = ctx.scope();
        return new CrmOwnerScope(
                scope == CrmScope.ALL,
                scope == CrmScope.OWN,
                scope == CrmScope.TEAM,
                ctx.currentUserId(),
                nonEmpty(scope == CrmScope.ALL ? null : ctx.teamUserIds()),
                nonEmpty(scope == CrmScope.TEAM ? ctx.teamIds() : null),
                scope != CrmScope.TEAM || ctx.allHotels(),
                nonEmpty(scope == CrmScope.TEAM ? ctx.propertyIds() : null)
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

    /**
     * Opportunities and events also carry an optional hotel. The owner always sees their own record; a team
     * sees a record only when its teams cover the hotel, and a chain-wide record (no hotel) only when they
     * cover every hotel.
     */
    public boolean allows(Long ownerUserId, Long teamId, Long propertyTenantId) {
        if (all) {
            return true;
        }
        if (ownerUserId != null && ownerUserId.equals(currentUserId)) {
            return true;
        }
        return team && allows(ownerUserId, teamId) && (allHotels
                || (propertyTenantId != null && propertyIds.contains(propertyTenantId)));
    }

    private static Collection<Long> nonEmpty(Collection<Long> ids) {
        return ids == null || ids.isEmpty() ? NONE : ids;
    }
}
