package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.security.CrmRole;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.CrmAccessContext;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

/**
 * Who may change prices, seasons and sales catalogs: SUPER_ADMIN / ADMIN, CRM REVENUE_MANAGER, or back-office STAFF
 * without a sales CRM role (same rule as product prices). Any logged-in user (not m2m) may read.
 */
@Component
public class PricingAccess {
    static final Set<CrmRole> SALES_ROLES = Set.of(CrmRole.SALES_REP, CrmRole.SALES_MANAGER, CrmRole.EVENT_COORDINATOR);

    private final AuthUserAccessor authUserAccessor;
    private final CrmAccessContext accessContext;

    public PricingAccess(AuthUserAccessor authUserAccessor, CrmAccessContext accessContext) {
        this.authUserAccessor = authUserAccessor;
        this.accessContext = accessContext;
    }

    public AppUser requireRead() {
        return authUserAccessor.requireAppUser();
    }

    public void requireWrite(String what) {
        AppUser user = authUserAccessor.requireAppUser();
        if (!canWrite(user, accessContext.crmRoles())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only admins and revenue managers can change " + what);
        }
    }

    static boolean canWrite(AppUser user, Set<CrmRole> crmRoles) {
        boolean admin = user.getRole() == AppUser.Role.SUPER_ADMIN || user.getRole() == AppUser.Role.ADMIN;
        boolean revenue = crmRoles.contains(CrmRole.REVENUE_MANAGER);
        boolean backOffice = user.getRole() == AppUser.Role.STAFF && crmRoles.stream().noneMatch(SALES_ROLES::contains);
        return admin || revenue || backOffice;
    }
}
