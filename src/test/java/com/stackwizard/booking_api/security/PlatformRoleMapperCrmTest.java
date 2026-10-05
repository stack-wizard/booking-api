package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRoleMapperCrmTest {

    @Test
    void mapsCrmRolesWithoutCollapsing() {
        Set<CrmRole> roles = PlatformRoleMapper.mapCrmRoles(List.of(
                "BOOKING_SALES_REP", "BOOKING_EVENT_COORDINATOR", "BOOKING_STAFF"));
        assertThat(roles).containsExactlyInAnyOrder(CrmRole.SALES_REP, CrmRole.EVENT_COORDINATOR);
    }

    @Test
    void mapHighestStillIgnoresCrmRoles() {
        assertThat(PlatformRoleMapper.mapHighest(List.of("BOOKING_SALES_MANAGER")))
                .isNull();
    }

    @Test
    void catalogUnionsPermissionsAndWidensScope() {
        Set<CrmRole> roles = EnumSet.of(CrmRole.SALES_REP, CrmRole.REVENUE_MANAGER);
        assertThat(CrmPermissionCatalog.permissionsFor(roles, AppUser.Role.STAFF))
                .contains(CrmPermission.ACCOUNT_WRITE, CrmPermission.REPORT_READ);
        assertThat(CrmPermissionCatalog.scopeFor(roles, AppUser.Role.STAFF)).isEqualTo(CrmScope.ALL);
    }

    @Test
    void adminGetsAllPermissions() {
        assertThat(CrmPermissionCatalog.permissionsFor(Set.of(), AppUser.Role.ADMIN))
                .containsExactlyInAnyOrder(CrmPermission.values());
        assertThat(CrmPermissionCatalog.scopeFor(Set.of(), AppUser.Role.SUPER_ADMIN))
                .isEqualTo(CrmScope.ALL);
    }
}
