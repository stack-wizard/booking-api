package com.stackwizard.booking_api.security;

import com.stackwizard.booking_api.config.BookingDevProperties;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.service.PlatformTenantResolver;
import com.stackwizard.booking_api.service.PlatformTenantResolver.TenantScope;
import com.stackwizard.booking_api.service.PlatformUserSyncService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PlatformAuthFilterTest {

    @Mock
    private PlatformTenantResolver tenantResolver;
    @Mock
    private PlatformUserSyncService userSyncService;

    private final BookingDevProperties devProperties = new BookingDevProperties();

    private PlatformAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new PlatformAuthFilter(tenantResolver, userSyncService, devProperties);
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        TenantContext.clear();
    }

    @Test
    void userTokenProvisionsTenantAndSyncsUser() {
        UUID platformTenantId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        UUID platformUserId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        Jwt jwt = jwt(platformUserId.toString(), Map.of(
                "preferred_username", "admin",
                "mikos_tenant_id", platformTenantId.toString(),
                "mikos_tenant_ids", List.of(platformTenantId.toString()),
                "mikos_applications", List.of("BOOKING"),
                "mikos_roles", List.of("BOOKING_ADMIN")
        ));

        when(tenantResolver.resolveScope(platformTenantId, null, false)).thenReturn(new TenantScope(1L, 1L));
        when(userSyncService.syncUser(eq(platformUserId), eq("admin"), eq(1L), any()))
                .thenReturn(AppUser.builder().id(9L).username("admin").role(AppUser.Role.ADMIN).tenantId(1L).build());

        MockHttpServletRequest request = new MockHttpServletRequest();
        filter.authenticateRequest(request, jwt);

        verify(tenantResolver).resolveScope(platformTenantId, null, false);
        verify(userSyncService).syncUser(eq(platformUserId), eq("admin"), eq(1L), any());
        assertThat(request.getAttribute(PlatformAuthFilter.ATTR_APP_USER)).isNotNull();
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
    }

    @Test
    void m2mRequiresExistingMappingAndDoesNotSyncUser() {
        UUID platformTenantId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        Jwt jwt = jwt("booking-cms", Map.of(
                "token_use", "m2m",
                "scope", "booking.api"
        ));
        when(tenantResolver.resolveScope(platformTenantId, null, false)).thenReturn(new TenantScope(1L, 1L));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", platformTenantId.toString());
        filter.authenticateRequest(request, jwt);

        verify(tenantResolver).resolveScope(platformTenantId, null, false);
        verifyNoInteractions(userSyncService);
        assertThat(TenantContext.getTenantId()).isEqualTo(1L);
    }

    @Test
    void orgHeaderWithPropertyHeaderSetsBothLevels() {
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID propertyId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        UUID platformUserId = UUID.randomUUID();
        Jwt jwt = jwt(platformUserId.toString(), Map.of(
                "preferred_username", "sales",
                "mikos_tenant_ids", List.of(orgId.toString()),
                "mikos_applications", List.of("BOOKING"),
                "mikos_roles", List.of("BOOKING_ADMIN")
        ));
        when(tenantResolver.resolveScope(orgId, propertyId, false)).thenReturn(new TenantScope(1L, 2L));
        when(userSyncService.syncUser(eq(platformUserId), eq("sales"), eq(1L), any()))
                .thenReturn(AppUser.builder().id(9L).username("sales").role(AppUser.Role.ADMIN).tenantId(1L).build());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", orgId.toString());
        request.addHeader("X-Property-Id", propertyId.toString());
        filter.authenticateRequest(request, jwt);

        assertThat(TenantContext.getOrgTenantId()).isEqualTo(1L);
        assertThat(TenantContext.getPropertyTenantId()).isEqualTo(2L);
    }

    @Test
    void hotelOnlyMembershipMayUseOrgHeaderTogetherWithItsHotel() {
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID propertyId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        UUID platformUserId = UUID.randomUUID();
        Jwt jwt = jwt(platformUserId.toString(), Map.of(
                "preferred_username", "frontdesk",
                "mikos_tenant_ids", List.of(propertyId.toString()),
                "mikos_applications", List.of("BOOKING"),
                "mikos_roles", List.of("BOOKING_ADMIN")
        ));
        when(tenantResolver.resolveScope(orgId, propertyId, false)).thenReturn(new TenantScope(1L, 2L));
        when(userSyncService.syncUser(eq(platformUserId), eq("frontdesk"), eq(1L), any()))
                .thenReturn(AppUser.builder().id(9L).username("frontdesk").role(AppUser.Role.ADMIN).tenantId(1L).build());

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", orgId.toString());
        request.addHeader("X-Property-Id", propertyId.toString());
        filter.authenticateRequest(request, jwt);

        assertThat(TenantContext.getPropertyTenantId()).isEqualTo(2L);
    }

    @Test
    void hotelOnlyMembershipCannotUseOrgHeaderWithoutHotel() {
        UUID orgId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID propertyId = UUID.fromString("21111111-1111-1111-1111-111111111111");
        Jwt jwt = jwt(UUID.randomUUID().toString(), Map.of(
                "mikos_tenant_ids", List.of(propertyId.toString()),
                "mikos_applications", List.of("BOOKING"),
                "mikos_roles", List.of("BOOKING_ADMIN")
        ));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Tenant-Id", orgId.toString());
        assertThatThrownBy(() -> filter.authenticateRequest(request, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("allow-list");
        verifyNoInteractions(tenantResolver);
    }

    @Test
    void userWithoutBookingAppIsForbidden() {
        UUID platformTenantId = UUID.randomUUID();
        UUID platformUserId = UUID.randomUUID();
        Jwt jwt = jwt(platformUserId.toString(), Map.of(
                "mikos_tenant_id", platformTenantId.toString(),
                "mikos_applications", List.of("VOID"),
                "mikos_roles", List.of("BOOKING_ADMIN")
        ));

        MockHttpServletRequest request = new MockHttpServletRequest();
        assertThatThrownBy(() -> filter.authenticateRequest(request, jwt))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("BOOKING");
        verifyNoInteractions(tenantResolver);
    }

    private static Jwt jwt(String subject, Map<String, Object> claims) {
        Map<String, Object> allClaims = new HashMap<>(claims);
        allClaims.put("sub", subject);
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(3600),
                Map.of("alg", "none"),
                allClaims
        );
    }
}
