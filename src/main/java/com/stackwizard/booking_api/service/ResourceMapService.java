package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class ResourceMapService {
    private final ResourceMapRepository repo;

    public ResourceMapService(ResourceMapRepository repo) { this.repo = repo; }

    public List<ResourceMap> findAll() { return repo.findAll(); }
    public Optional<ResourceMap> findById(Long id) { return repo.findById(id); }
    public List<ResourceMap> findByTenantId(Long tenantId) { return repo.findByTenantId(tenantId); }
    public ResourceMap save(ResourceMap map) {
        validate(map);
        return repo.save(map);
    }
    public void deleteById(Long id) { repo.deleteById(id); }

    private void validate(ResourceMap map) {
        if (map.getTenantId() == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (map.getName() == null || map.getName().isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (map.getValidFrom() != null && map.getValidTo() != null && map.getValidTo().isBefore(map.getValidFrom())) {
            throw new IllegalArgumentException("validTo must be >= validFrom");
        }

        if (map.getParentMap() != null && map.getParentMap().getId() != null) {
            ResourceMap parent = repo.findById(map.getParentMap().getId())
                    .orElseThrow(() -> new IllegalArgumentException("parentMap not found"));
            if (!map.getTenantId().equals(parent.getTenantId())) {
                throw new IllegalArgumentException("parentMap tenant mismatch");
            }
            map.setParentMap(parent);
            // Child maps are not used in availability root selection; keep root overlap rule only for parent-less maps.
            return;
        }

        Long locationNodeId = map.getLocationNode() != null ? map.getLocationNode().getId() : null;
        String validFrom = toDateLiteral(map.getValidFrom());
        String validTo = toDateLiteral(map.getValidTo());
        if (repo.existsOverlappingRootMap(map.getTenantId(), locationNodeId, validFrom, validTo, map.getId())) {
            throw new IllegalStateException("Overlapping root map period exists for this location");
        }
    }

    private String toDateLiteral(LocalDate value) {
        return value != null ? value.toString() : null;
    }
}
