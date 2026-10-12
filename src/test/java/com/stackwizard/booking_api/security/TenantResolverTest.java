package com.stackwizard.booking_api.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TenantResolverTest {

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void selectedHotelNarrowsChainLists() {
        TenantContext.setScope(1L, 2L);
        assertThat(TenantResolver.selectedHotelId()).isEqualTo(2L);
    }

    @Test
    void allHotelsAndStandaloneHotelDoNotNarrow() {
        TenantContext.setScope(1L, null);
        assertThat(TenantResolver.selectedHotelId()).isNull();

        TenantContext.setTenantId(3L);
        assertThat(TenantResolver.selectedHotelId()).isNull();
    }
}
