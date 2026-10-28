package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.model.AppUser;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LoyaltyAccessTest {

    @Test
    void rolesMapToPermissions() {
        assertThat(LoyaltyAccess.permissionsFor(AppUser.Role.ADMIN, List.of())).isEqualTo(EnumSet.allOf(LoyaltyPermission.class));
        assertThat(LoyaltyAccess.permissionsFor(AppUser.Role.STAFF, List.of())).containsExactly(LoyaltyPermission.READ);
        assertThat(LoyaltyAccess.permissionsFor(null, List.of("booking_loyalty_support"))).containsExactly(LoyaltyPermission.READ);
        assertThat(LoyaltyAccess.permissionsFor(null, List.of("BOOKING_LOYALTY_OWNER")))
                .contains(LoyaltyPermission.ADJUST, LoyaltyPermission.CONFIG, LoyaltyPermission.IMPORT)
                .doesNotContain(LoyaltyPermission.INTEGRATION_CONFIG);
        assertThat(LoyaltyAccess.permissionsFor(null, List.of("BOOKING_CASHIER"))).isEmpty();
    }
}
