package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.PriceProfile;
import com.stackwizard.booking_api.repository.PriceProfileRepository;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Price profiles belong to the chain. A profile with {@code propertyTenantId} is the price list of one hotel,
 * which wins over the chain-wide profile for that hotel.
 */
@Service
public class PriceProfileService {
    private final PriceProfileRepository repo;
    private final TenantHierarchy hierarchy;

    public PriceProfileService(PriceProfileRepository repo, TenantHierarchy hierarchy) {
        this.repo = repo;
        this.hierarchy = hierarchy;
    }

    public List<PriceProfile> findAll() {
        return repo.findByTenantIdOrderByIdAsc(TenantResolver.requireOrgTenantId());
    }

    public Optional<PriceProfile> findById(Long id) {
        return repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId());
    }

    @Transactional
    public PriceProfile create(PriceProfile profile) {
        Long tenantId = TenantResolver.requireOrgTenantId();
        profile.setId(null);
        profile.setTenantId(tenantId);
        requireHotelOfChain(tenantId, profile.getPropertyTenantId());
        return repo.save(profile);
    }

    @Transactional
    public Optional<PriceProfile> update(Long id, PriceProfile changes) {
        Long tenantId = TenantResolver.requireOrgTenantId();
        return repo.findByIdAndTenantId(id, tenantId).map(existing -> {
            requireHotelOfChain(tenantId, changes.getPropertyTenantId());
            existing.setName(changes.getName());
            existing.setCurrency(changes.getCurrency());
            existing.setPropertyTenantId(changes.getPropertyTenantId());
            existing.setReservationRequestType(changes.getReservationRequestType());
            return repo.save(existing);
        });
    }

    @Transactional
    public boolean deleteById(Long id) {
        Optional<PriceProfile> existing = repo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId());
        existing.ifPresent(repo::delete);
        return existing.isPresent();
    }

    private void requireHotelOfChain(Long orgTenantId, Long propertyTenantId) {
        if (propertyTenantId != null) {
            hierarchy.requirePropertyOf(propertyTenantId, orgTenantId);
        }
    }
}
