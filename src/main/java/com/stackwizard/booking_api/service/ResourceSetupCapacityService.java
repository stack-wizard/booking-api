package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.model.ResourceSetupCapacity;
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
    /** Accommodation; a room type of a hotel is a POOL resource of one of these types. */
    static final Set<String> ROOM_RESOURCE_TYPES = Set.of("ROOM", "APARTMENT");

    /** Meeting space (hall / office / composition), not a sleeping-room pool. */
    public static boolean isFunctionSpace(Resource resource) {
        return resource != null
                && isActive(resource)
                && resource.getResourceType() != null && resource.getResourceType().getCode() != null
                && SPACE_RESOURCE_TYPES.contains(resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT));
    }

    /** Active room-type pool: what a room block is placed on and what the room grid shows. */
    public static boolean isRoomType(Resource resource) {
        return AllocationCapacityService.isPool(resource)
                && isActive(resource)
                && resource.getResourceType() != null && resource.getResourceType().getCode() != null
                && ROOM_RESOURCE_TYPES.contains(resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT));
    }

    private final ResourceSetupCapacityRepository capacityRepo;
    private final ResourceRepository resourceRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final AllocationCapacityService capacityService;
    private final CrmAccessContext accessContext;
    private final CrmLookupService lookupService;

    public ResourceSetupCapacityService(ResourceSetupCapacityRepository capacityRepo,
                                        ResourceRepository resourceRepo,
                                        ResourceCompositionRepository compositionRepo,
                                        AllocationCapacityService capacityService,
                                        CrmAccessContext accessContext,
                                        CrmLookupService lookupService) {
        this.capacityRepo = capacityRepo;
        this.resourceRepo = resourceRepo;
        this.compositionRepo = compositionRepo;
        this.capacityService = capacityService;
        this.accessContext = accessContext;
        this.lookupService = lookupService;
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
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        Set<String> previous = new HashSet<>();
        capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(tenantId, resourceId)
                .forEach(c -> previous.add(c.getSetupStyle()));
        Set<String> seen = new HashSet<>();
        List<ResourceSetupCapacity> rows = new ArrayList<>();
        for (ResourceSetupCapacity setup : incoming) {
            if (setup == null || setup.getSetupStyle() == null || setup.getSetupStyle().isBlank()) {
                throw new IllegalArgumentException("setupStyle is required");
            }
            String style = previous.stream().filter(p -> p.equalsIgnoreCase(setup.getSetupStyle().trim())).findFirst()
                    .orElseGet(() -> lookupService.requireCode(orgTenantId, CrmLookup.Kind.SETUP_STYLE, setup.getSetupStyle(), null));
            if (setup.getCapacity() == null || setup.getCapacity() <= 0) {
                throw new IllegalArgumentException("capacity must be > 0 for " + style);
            }
            if (!seen.add(style.toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate setup style " + style);
            }
            rows.add(ResourceSetupCapacity.builder()
                    .tenantId(tenantId)
                    .resourceId(resourceId)
                    .setupStyle(style)
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
    public Optional<Integer> capacityFor(Long tenantId, Long resourceId, String setupStyle) {
        return capacityRepo.findByTenantIdAndResourceIdAndSetupStyle(tenantId, resourceId, setupStyle)
                .map(ResourceSetupCapacity::getCapacity);
    }

    /**
     * Spaces that seat {@code minPax} in the setup style (any style when null) and, when a window is given,
     * have no active allocation on themselves or their composition members.
     */
    @Transactional(readOnly = true)
    public List<SpaceCandidate> searchSpaces(Integer minPax,
                                             String setupStyle,
                                             LocalDateTime from,
                                             LocalDateTime to) {
        return searchSpaces(TenantResolver.requireTenantId(), minPax, setupStyle, from, to, Set.of());
    }

    /** Same search in a given hotel; {@code excludeReservationIds} do not count as occupying (the booking's own lines). */
    @Transactional(readOnly = true)
    public List<SpaceCandidate> searchSpaces(Long tenantId,
                                             Integer minPax,
                                             String setupStyle,
                                             LocalDateTime from,
                                             LocalDateTime to,
                                             Set<Long> excludeReservationIds) {
        int pax = minPax == null ? 1 : minPax;
        if (pax <= 0) {
            throw new IllegalArgumentException("minPax must be > 0");
        }
        if ((from == null) != (to == null) || (from != null && !to.isAfter(from))) {
            throw new IllegalArgumentException("from and to must both be set and to must be after from");
        }
        Map<Long, List<ResourceSetupCapacity>> byResource = new LinkedHashMap<>();
        String style = setupStyle == null || setupStyle.isBlank() ? null : setupStyle.trim().toUpperCase(Locale.ROOT);
        for (ResourceSetupCapacity c : capacityRepo.findCandidates(tenantId, pax, style)) {
            byResource.computeIfAbsent(c.getResourceId(), k -> new ArrayList<>()).add(c);
        }
        List<SpaceCandidate> out = new ArrayList<>();
        for (Map.Entry<Long, List<ResourceSetupCapacity>> entry : byResource.entrySet()) {
            Resource resource = resourceRepo.findByIdWithType(entry.getKey()).orElse(null);
            if (resource == null || !tenantId.equals(resource.getTenantId()) || !isActive(resource)) {
                continue;
            }
            boolean available = from == null || isFree(resource, from, to, excludeReservationIds);
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

    /** Every active space of the hotel, regardless of setup capacity (offices, spaces without setups). */
    @Transactional(readOnly = true)
    public List<SpaceCandidate> listSpaces(Long tenantId, LocalDateTime from, LocalDateTime to, Set<Long> excludeReservationIds) {
        List<SpaceCandidate> out = new ArrayList<>();
        for (Resource resource : resourceRepo.findByTenantId(tenantId)) {
            String code = resource.getResourceType() != null && resource.getResourceType().getCode() != null
                    ? resource.getResourceType().getCode().trim().toUpperCase(Locale.ROOT) : "";
            if (!SPACE_RESOURCE_TYPES.contains(code) || !isActive(resource)) {
                continue;
            }
            out.add(new SpaceCandidate(
                    resource.getId(),
                    resource.getName(),
                    code,
                    resource.getAreaSqm() != null ? resource.getAreaSqm().toPlainString() : null,
                    resource.getFloor(),
                    resource.getNaturalLight(),
                    capacityRepo.findByTenantIdAndResourceIdOrderBySetupStyleAsc(tenantId, resource.getId()).stream()
                            .map(c -> new SetupCapacity(c.getSetupStyle(), c.getCapacity()))
                            .toList(),
                    from == null || isFree(resource, from, to, excludeReservationIds)));
        }
        return out;
    }

    boolean isFree(Resource resource, LocalDateTime from, LocalDateTime to) {
        return isFree(resource, from, to, Set.of());
    }

    boolean isFree(Resource resource, LocalDateTime from, LocalDateTime to, Set<Long> excludeReservationIds) {
        if (capacityService.freeUnits(resource, from, to, excludeReservationIds) < 1) {
            return false;
        }
        for (ResourceComposition member : compositionRepo.findByParentResourceId(resource.getId())) {
            Resource memberResource = member.getMemberResource();
            int needed = member.getQty() != null ? member.getQty() : 1;
            if (memberResource != null && capacityService.freeUnits(memberResource, from, to, excludeReservationIds) < needed) {
                return false;
            }
        }
        return true;
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

    public record SetupCapacity(String setupStyle, Integer capacity) {
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
