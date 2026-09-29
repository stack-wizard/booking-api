package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformRoleMapperTest {

    @Test
    void mapsPlatformAdminToSuperAdmin() {
        assertThat(PlatformRoleMapper.mapHighest(List.of("PLATFORM_ADMIN", "BOOKING_ADMIN")))
                .isEqualTo(AppUser.Role.SUPER_ADMIN);
    }

    @Test
    void highestBookingRoleWins() {
        assertThat(PlatformRoleMapper.mapHighest(List.of("BOOKING_CASHIER", "BOOKING_ADMIN", "BOOKING_STAFF")))
                .isEqualTo(AppUser.Role.ADMIN);
        assertThat(PlatformRoleMapper.mapHighest(List.of("BOOKING_CASHIER", "BOOKING_STAFF")))
                .isEqualTo(AppUser.Role.STAFF);
        assertThat(PlatformRoleMapper.mapHighest(List.of("BOOKING_CASHIER")))
                .isEqualTo(AppUser.Role.CASHIER);
    }

    @Test
    void ignoresUnrelatedRoles() {
        assertThat(PlatformRoleMapper.mapHighest(List.of("VOID_OPERATOR", "TENANT_ADMIN")))
                .isNull();
    }
}
