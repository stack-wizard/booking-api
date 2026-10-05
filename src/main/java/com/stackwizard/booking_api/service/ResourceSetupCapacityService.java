package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.ResourceSetupCapacityRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class ResourceSetupCapacityService {
    static final Set<String> SPACE_RESOURCE_TYPES = Set.of("MEETING_ROOM", "MEETING_OFFICE", "COMPOSITION");

    private final ResourceSetupCapacityRepository capacityRepo;
    private final ResourceRepository resourceRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final AllocationRepository allocationRepo;
    private final CrmAccessContext accessContext;

    public ResourceSetupCapacityService(ResourceSetupCapacityRepository capacityRepo,
                                        ResourceRepository resourceRepo,
                                        ResourceCompositionRepository compositionRepo,
                                        AllocationRepository allocationRepo,
                                        CrmAccessContext accessContext) {
        this.capacityRepo = capacityRepo;
        this.resourceRepo = resourceRepo;
        this.compositionRepo = compositionRepo;
        this.allocationRepo = allocationRepo;
        this.accessContext = accessContext;
    }

    public List<ResourceSetupCapacity> forResource(Long resourceId) {
        Long tenantId = TenantResolver.requireTenantId();
        requireSpaceResource(tenantId, resourceId);
        return capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(tenantId, resourceId);
    }

    public List<ResourceSetupCapacity> all() {
        return capacityRepo.findByTenantIdOrderByResourceIdAscSetupStyleAsc(TenantResolver.requireTenantId());
    }

    @Transactional
    public List<ResourceSetupCapacity> replace(Long resourceId, List<ResourceSetupCapacity> setups) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long tenantId = TenantResolver.requireTenantId();
        requireSpaceResource(tenantId, resourceId);
        List<ResourceSetupCapacity> incoming = setups == null ? List.of() : setups;
        EnumSet<ResourceSetupCapacity.SetupStyle> seen = EnumSet.noneOf(ResourceSetupCapacity.SetupStyle.class);
        List<ResourceSetupCapacity> rows = new ArrayList<>();
        for (ResourceSetupCapacity setup : incoming) {
            if (setup == null || setup.getSetupStyle() == null) {
                throw new IllegalArgumentException("setupStyle is required");
            }
            if (setup.getCapacity() == null || setup.getCapacity() <= 0) {
                throw new IllegalArgumentException("capacity must be > 0 for " + setup.getSetupStyle());
            }
            if (!seen.add(setup.getSetupStyle())) {
                throw new IllegalArgumentException("Duplicate setup style " + setup.getSetupStyle());
            }
            rows.add(ResourceSetupCapacity.builder()
                    .tenantId(tenantId)
                    .resourceId(resourceId)
                    .setupStyle(setup.getSetupStyle())
                    .capacity(setup.getCapacity())
                    .notes(setup.getNotes())
                    .build());
        }
        capacityRepo.deleteByTenantIdAndResourceId(tenantId, resourceId);
        capacityRepo.flush();
        return capacityRepo.saveAll(rows);
    }

    /**
     * Capacity of a space for a setup style; empty when the space has no such setup.
     */
    public Optional<Integer> capacityFor(Long tenantId, Long resourceId, ResourceSetupCapacity.SetupStyle setupStyle) {
        return capacityRepo.findByTenantIdAndResourceIdAndSetupStyle(tenantId, resourceId, setupStyle)
                .map(ResourceSetupCapacity::getCapacity);
    }

    /**
     * Spaces that seat {@code minPax} in the setup style (any style when null) and, when a window is given,
     * have no active allocation on themselves or their composition members.
     */
    @Transactional(readOnly = true)
    public List<SpaceCandidate> searchSpaces(Integer minPax,
                                             ResourceSetupCapacity.SetupStyle setupStyle,
                                             LocalDateTime from,
                                             LocalDateTime to) {
        Long tenantId = TenantResolver.requireTenantId();
        int pax = minPax == null ? 1 : minPax;
        if (pax <= 0) {
            throw new IllegalArgumentException("minPax must be > 0");
        }
        if ((from == null) != (to == null) || (from != null && !to.isAfter(from))) {
            throw new IllegalArgumentException("from and to must both be set and to must be after from");
        }
        Map<Long, List<ResourceSetupCapacity>> byResource = new LinkedHashMap<>();
        for (ResourceSetupCapacity c : capacityRepo.findCandidates(tenantId, pax, setupStyle)) {
            byResource.computeIfAbsent(c.getResourceId(), k -> new ArrayList<>()).add(c);
        }
        List<SpaceCandidate> out = new ArrayList<>();
        for (Map.Entry<Long, List<ResourceSetupCapacity>> entry : byResource.entrySet()) {
            Resource resource = resourceRepo.findByIdWithType(entry.getKey()).orElse(null);
            if (resource == null || !tenantId.equals(resource.getTenantId()) || !isActive(resource)) {
                continue;
            }
            boolean available = from == null || isFree(resource, from, to);
            out.add(new SpaceCandidate(
                    resource.getId(),
                    resource.getName(),
                    resource.getResourceType() != null ? resource.getResourceType().getCode() : null,
                    resource.getAreaSqm() != null ? resource.getAreaSqm().toPlainString() : null,
                    resource.getFloor(),
                    resource.getNaturalLight(),
                    entry.getValue().stream()
                            .map(c -> new SetupCapacity(c.getSetupStyle(), c.getCapacity()))
                            .toList(),
                    available));
        }
        return out;
    }

    boolean isFree(Resource resource, LocalDateTime from, LocalDateTime to) {
        Set<Long> ids = new HashSet<>();
        ids.add(resource.getId());
        for (ResourceComposition member : compositionRepo.findByParentResourceId(resource.getId())) {
            if (member.getMemberResource() != null) {
                ids.add(member.getMemberResource().getId());
            }
        }
        List<Allocation> busy = allocationRepo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
                List.copyOf(ids), to, from);
        return busy.isEmpty();
    }

    Resource requireSpaceResource(Long tenantId, Long resourceId) {
        if (resourceId == null) {
            throw new IllegalArgumentException("resourceId is required");
        }
        Resource resource = resourceRepo.findByIdWithType(resourceId)
                .filter(r -> tenantId.equals(r.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Resource not found: " + resourceId));
        String code = resource.getResourceType() != null && resource.getResourceType().getCode() != null
                ? resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT)
                : "";
        if (!SPACE_RESOURCE_TYPES.contains(code)) {
            throw new IllegalArgumentException("Resource type must be one of " + SPACE_RESOURCE_TYPES + ", got " + code);
        }
        return resource;
    }

    private static boolean isActive(Resource resource) {
        return resource.getStatus() == null || "ACTIVE".equalsIgnoreCase(resource.getStatus());
    }

    public record SetupCapacity(ResourceSetupCapacity.SetupStyle setupStyle, Integer capacity) {
    }

    public record SpaceCandidate(Long resourceId,
                                 String name,
                                 String resourceType,
                                 String areaSqm,
                                 String floor,
                                 Boolean naturalLight,
                                 Collection<SetupCapacity> setups,
                                 boolean available) {
    }
}
