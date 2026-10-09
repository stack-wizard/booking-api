package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.repository.PlatformTenantMappingRepository;
import org.mockito.Mockito;

/** A hierarchy in which every tenant is its own organization and hotel (legacy single tenant). */
final class TenantHierarchyTestSupport {
    private TenantHierarchyTestSupport() {
    }

    static TenantHierarchy standalone() {
        return new TenantHierarchy(Mockito.mock(PlatformTenantMappingRepository.class));
    }
}
