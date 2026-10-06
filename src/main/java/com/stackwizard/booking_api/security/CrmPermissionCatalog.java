package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;

import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

public final class CrmPermissionCatalog {

    private static final Map<CrmRole, Set<CrmPermission>> PERMISSIONS = new EnumMap<>(CrmRole.class);
    private static final Map<CrmRole, CrmScope> SCOPES = new EnumMap<>(CrmRole.class);

    static {
        Set<CrmPermission> rep = EnumSet.of(
                CrmPermission.ACCOUNT_READ,
                CrmPermission.ACCOUNT_WRITE,
                CrmPermission.OPPORTUNITY_READ,
                CrmPermission.OPPORTUNITY_WRITE,
                CrmPermission.ACTIVITY_WRITE,
                CrmPermission.EVENT_READ,
                CrmPermission.EVENT_WRITE
        );
        PERMISSIONS.put(CrmRole.SALES_REP, Collections.unmodifiableSet(rep));
        SCOPES.put(CrmRole.SALES_REP, CrmScope.OWN);

        Set<CrmPermission> manager = EnumSet.copyOf(rep);
        manager.add(CrmPermission.OPPORTUNITY_CLOSE);
        manager.add(CrmPermission.PIPELINE_CONFIG);
        manager.add(CrmPermission.REPORT_READ);
        manager.add(CrmPermission.QUOTE_APPROVE);
        PERMISSIONS.put(CrmRole.SALES_MANAGER, Collections.unmodifiableSet(manager));
        SCOPES.put(CrmRole.SALES_MANAGER, CrmScope.TEAM);

        PERMISSIONS.put(CrmRole.EVENT_COORDINATOR, Collections.unmodifiableSet(EnumSet.of(
                CrmPermission.ACCOUNT_READ,
                CrmPermission.OPPORTUNITY_READ,
                CrmPermission.ACTIVITY_WRITE,
                CrmPermission.EVENT_READ,
                CrmPermission.EVENT_WRITE
        )));
        SCOPES.put(CrmRole.EVENT_COORDINATOR, CrmScope.TEAM);

        PERMISSIONS.put(CrmRole.REVENUE_MANAGER, Collections.unmodifiableSet(EnumSet.of(
                CrmPermission.ACCOUNT_READ,
                CrmPermission.OPPORTUNITY_READ,
                CrmPermission.REPORT_READ,
                CrmPermission.EVENT_READ
        )));
        SCOPES.put(CrmRole.REVENUE_MANAGER, CrmScope.ALL);
    }

    private CrmPermissionCatalog() {
    }

    public static Set<CrmPermission> permissionsFor(Set<CrmRole> roles, AppUser.Role bookingRole) {
        if (bookingRole == AppUser.Role.SUPER_ADMIN || bookingRole == AppUser.Role.ADMIN) {
            return EnumSet.allOf(CrmPermission.class);
        }
        EnumSet<CrmPermission> out = EnumSet.noneOf(CrmPermission.class);
        if (roles != null) {
            for (CrmRole role : roles) {
                Set<CrmPermission> perms = PERMISSIONS.get(role);
                if (perms != null) {
                    out.addAll(perms);
                }
            }
        }
        return out;
    }

    public static CrmScope scopeFor(Set<CrmRole> roles, AppUser.Role bookingRole) {
        if (bookingRole == AppUser.Role.SUPER_ADMIN || bookingRole == AppUser.Role.ADMIN) {
            return CrmScope.ALL;
        }
        CrmScope widest = CrmScope.OWN;
        if (roles != null) {
            for (CrmRole role : roles) {
                CrmScope scope = SCOPES.get(role);
                if (scope == null) {
                    continue;
                }
                if (scope == CrmScope.ALL) {
                    return CrmScope.ALL;
                }
                if (scope == CrmScope.TEAM) {
                    widest = CrmScope.TEAM;
                }
            }
        }
        return widest;
    }
}
