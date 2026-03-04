package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.ResourceMapPeriodDto;
import com.stackwizard.booking_api.dto.ResourceMapPeriodsResponse;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.Comparator;
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

    @Transactional(readOnly = true)
    public ResourceMapPeriodsResponse getMapPeriods(Long tenantId, Long locationId, LocalDate fromDate) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (fromDate == null) {
            throw new IllegalArgumentException("fromDate is required");
        }

        List<ResourceMap> rootMaps = repo.findByTenantId(tenantId).stream()
                .filter(map -> map.getParentMap() == null)
                .filter(map -> locationId == null || (map.getLocationNode() != null && locationId.equals(map.getLocationNode().getId())))
                .filter(map -> map.getValidTo() == null || !map.getValidTo().isBefore(fromDate))
                .sorted(Comparator
                        .comparing((ResourceMap map) -> effectiveStart(map, fromDate))
                        .thenComparing(ResourceMap::getCreatedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        List<ResourceMapPeriodDto> periods = rootMaps.stream()
                .map(map -> ResourceMapPeriodDto.builder()
                        .mapId(map.getId())
                        .locationNodeId(map.getLocationNode() != null ? map.getLocationNode().getId() : null)
                        .mapName(map.getName())
                        .validFrom(map.getValidFrom())
                        .validTo(map.getValidTo())
                        .build())
                .toList();

        LocalDate firstDate = rootMaps.stream()
                .map(map -> effectiveStart(map, fromDate))
                .min(LocalDate::compareTo)
                .orElse(null);

        LocalDate lastDate = rootMaps.stream().anyMatch(map -> map.getValidTo() == null)
                ? null
                : rootMaps.stream()
                .map(ResourceMap::getValidTo)
                .max(LocalDate::compareTo)
                .orElse(null);

        return ResourceMapPeriodsResponse.builder()
                .tenantId(tenantId)
                .locationId(locationId)
                .fromDate(fromDate)
                .firstDate(firstDate)
                .lastDate(lastDate)
                .periods(periods)
                .build();
    }

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

    private LocalDate effectiveStart(ResourceMap map, LocalDate fromDate) {
        if (map.getValidFrom() == null || map.getValidFrom().isBefore(fromDate)) {
            return fromDate;
        }
        return map.getValidFrom();
    }
}
