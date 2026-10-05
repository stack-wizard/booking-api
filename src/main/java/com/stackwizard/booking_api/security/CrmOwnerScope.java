package com.stackwizard.booking_api.security;

import java.util.Collection;
import java.util.Set;

public record CrmOwnerScope(boolean all, boolean own, boolean team, Long currentUserId, Collection<Long> teamUserIds) {

    public static CrmOwnerScope from(CrmAccessContext ctx) {
        CrmScope scope = ctx.scope();
        return new CrmOwnerScope(
                scope == CrmScope.ALL,
                scope == CrmScope.OWN,
                scope == CrmScope.TEAM,
                ctx.currentUserId(),
                scope == CrmScope.ALL ? Set.of(-1L) : ctx.teamUserIds()
        );
    }

    public boolean allows(Long ownerUserId) {
        if (all) {
            return true;
        }
        if (ownerUserId == null) {
            return false;
        }
        if (own) {
            return ownerUserId.equals(currentUserId);
        }
        if (team) {
            return teamUserIds.contains(ownerUserId);
        }
        return false;
    }
}
