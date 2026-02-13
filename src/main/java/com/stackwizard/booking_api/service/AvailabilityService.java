package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AvailabilityMapDto;
import com.stackwizard.booking_api.dto.AvailabilityMapPositionDto;
import com.stackwizard.booking_api.dto.AvailabilityPriceDto;
import com.stackwizard.booking_api.dto.AvailabilityResourceDto;
import com.stackwizard.booking_api.dto.AvailabilityResourceRefDto;
import com.stackwizard.booking_api.dto.AvailabilityResponse;
import com.stackwizard.booking_api.dto.AvailabilitySlotDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.model.ResourceMap;
import com.stackwizard.booking_api.model.ResourceMapResource;
import com.stackwizard.booking_api.model.Uom;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import com.stackwizard.booking_api.repository.ResourceMapResourceRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.UomRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AvailabilityService {
    private static final String STATUS_AVAILABLE = "AVAILABLE";
    private static final String STATUS_PARTIAL = "PARTIAL";
    private static final String STATUS_UNAVAILABLE = "UNAVAILABLE";

    private final ResourceRepository resourceRepo;
    private final ResourceCompositionRepository compositionRepo;
    private final AllocationRepository allocationRepo;
    private final ProductRepository productRepo;
    private final UomRepository uomRepo;
    private final PriceListEntryRepository priceListRepo;
    private final ServiceCalendarService calendarService;
    private final ResourceMapRepository mapRepo;
    private final ResourceMapResourceRepository mapResourceRepo;

    public AvailabilityService(ResourceRepository resourceRepo,
                               ResourceCompositionRepository compositionRepo,
                               AllocationRepository allocationRepo,
                               ProductRepository productRepo,
                               PriceListEntryRepository priceListRepo,
                               ServiceCalendarService calendarService,
                               ResourceMapRepository mapRepo,
                               ResourceMapResourceRepository mapResourceRepo,
                               UomRepository uomRepo) {
        this.resourceRepo = resourceRepo;
        this.compositionRepo = compositionRepo;
        this.allocationRepo = allocationRepo;
        this.productRepo = productRepo;
        this.priceListRepo = priceListRepo;
        this.calendarService = calendarService;
        this.mapRepo = mapRepo;
        this.mapResourceRepo = mapResourceRepo;
        this.uomRepo = uomRepo;
    }

    @Transactional(readOnly = true)
    public AvailabilityResponse getAvailability(Long tenantId, LocalDate date, Long locationId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (date == null) {
            throw new IllegalArgumentException("date is required");
        }

        List<Resource> resources = locationId == null
                ? resourceRepo.findByTenantId(tenantId)
                : resourceRepo.findByTenantIdAndLocationId(tenantId, locationId);

        Map<Long, Resource> resourceById = resources.stream()
                .collect(Collectors.toMap(Resource::getId, r -> r));

        List<Long> resourceIds = resources.stream().map(Resource::getId).toList();

        Map<Long, List<Resource>> membersByParent = new HashMap<>();
        Map<Long, List<Resource>> parentsByMember = new HashMap<>();
        if (!resourceIds.isEmpty()) {
            List<ResourceComposition> parentLinks = compositionRepo.findByParentResourceIdIn(resourceIds);
            for (ResourceComposition link : parentLinks) {
                Resource parent = link.getParentResource();
                Resource member = link.getMemberResource();
                membersByParent.computeIfAbsent(parent.getId(), ignored -> new ArrayList<>()).add(member);
            }

            List<ResourceComposition> memberLinks = compositionRepo.findByMemberResourceIdIn(resourceIds);
            for (ResourceComposition link : memberLinks) {
                Resource parent = link.getParentResource();
                Resource member = link.getMemberResource();
                parentsByMember.computeIfAbsent(member.getId(), ignored -> new ArrayList<>()).add(parent);
            }
        }

        Map<Long, List<Product>> productsByResourceId = new HashMap<>();
        List<Product> products = productRepo.findByTenantId(tenantId);
        Map<Long, String> productNameById = new HashMap<>();
        for (Product product : products) {
            if (product.getResource() != null && product.getResource().getId() != null) {
                productsByResourceId.computeIfAbsent(product.getResource().getId(), ignored -> new ArrayList<>()).add(product);
            }
            productNameById.put(product.getId(), product.getName());
        }

        Map<String, String> uomNameByCode = new HashMap<>();
        List<Uom> uoms = uomRepo.findByActiveTrue();
        for (Uom uom : uoms) {
            if (uom.getCode() != null) {
                uomNameByCode.put(uom.getCode().toUpperCase(), uom.getName());
            }
        }

        Map<Long, List<PriceListEntry>> pricesByProductId = new HashMap<>();
        List<Long> productIds = products.stream().map(Product::getId).toList();
        if (!productIds.isEmpty()) {
            List<PriceListEntry> prices = priceListRepo.findForProductsOnDate(productIds, tenantId, date);
            for (PriceListEntry price : prices) {
                pricesByProductId.computeIfAbsent(price.getProductId(), ignored -> new ArrayList<>()).add(price);
            }
        }

        List<ResourceMap> maps = mapRepo.findByTenantId(tenantId);
        List<Long> mapIds = maps.stream().map(ResourceMap::getId).toList();
        Map<Long, ResourceMapResource> mapByResourceId = new HashMap<>();
        if (!mapIds.isEmpty()) {
            List<ResourceMapResource> mapResources = mapResourceRepo.findByResourceMapIdIn(mapIds);
            for (ResourceMapResource mapResource : mapResources) {
                Resource resource = mapResource.getResource();
                if (resource != null) {
                    mapByResourceId.put(resource.getId(), mapResource);
                }
            }
        }

        Map<Long, ServiceCalendarService.ServiceWindow> windowByLocation = new HashMap<>();
        Map<Long, Integer> gridMinutesByLocation = new HashMap<>();
        Map<Long, List<Allocation>> allocationsByResourceId = new HashMap<>();
        Map<Long, ServiceCalendarService.ServiceWindow> windowByResourceId = new HashMap<>();
        Map<Long, Integer> gridMinutesByResourceId = new HashMap<>();

        Map<Long, List<Resource>> resourcesByLocation = new HashMap<>();
        for (Resource resource : resources) {
            Long locId = resource.getLocation() != null ? resource.getLocation().getId() : null;
            resourcesByLocation.computeIfAbsent(locId, ignored -> new ArrayList<>()).add(resource);
        }

        for (Map.Entry<Long, List<Resource>> entry : resourcesByLocation.entrySet()) {
            Long locId = entry.getKey();
            ServiceCalendarService.ServiceWindow window = windowByLocation.computeIfAbsent(
                    locId,
                    ignored -> calendarService.windowFor(calendarService.calendarFor(tenantId, locId), date)
            );
            if (!gridMinutesByLocation.containsKey(locId)) {
                gridMinutesByLocation.put(
                        locId,
                        calendarService.calendarFor(tenantId, locId).getGridMinutes()
                );
            }

            List<Long> ids = entry.getValue().stream().map(Resource::getId).toList();
            if (!ids.isEmpty()) {
                List<Allocation> allocations = allocationRepo
                        .findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(ids, window.close(), window.open());
                for (Allocation allocation : allocations) {
                    allocationsByResourceId.computeIfAbsent(
                            allocation.getAllocatedResource().getId(),
                            ignored -> new ArrayList<>()
                    ).add(allocation);
                }
            }

            for (Resource resource : entry.getValue()) {
                windowByResourceId.put(resource.getId(), window);
                gridMinutesByResourceId.put(resource.getId(), gridMinutesByLocation.get(locId));
            }
        }

        Map<Long, List<Interval>> freeSlotsByResourceId = new HashMap<>();
        for (Resource resource : resources) {
            ServiceCalendarService.ServiceWindow window = windowByResourceId.get(resource.getId());
            List<Allocation> allocations = allocationsByResourceId.getOrDefault(resource.getId(), List.of());
            List<Interval> freeSlots = computeFreeSlots(window, allocations);
            freeSlotsByResourceId.put(resource.getId(), freeSlots);
        }

        Map<Long, List<Interval>> effectiveSlotsByResourceId = computeEffectiveSlots(
                resources, freeSlotsByResourceId, membersByParent, parentsByMember);
        List<AvailabilityResourceDto> resourceDtos = new ArrayList<>();
        for (Resource resource : resources) {
            ServiceCalendarService.ServiceWindow window = windowByResourceId.get(resource.getId());
            List<Interval> freeSlots = effectiveSlotsByResourceId.getOrDefault(resource.getId(), List.of());

            String status = statusFromFreeSlots(window, freeSlots);

            List<AvailabilityResourceRefDto> parents = parentsByMember
                    .getOrDefault(resource.getId(), List.of())
                    .stream()
                    .map(parent -> AvailabilityResourceRefDto.builder()
                            .tenantId(parent.getTenantId())
                            .id(parent.getId())
                            .code(parent.getCode())
                            .name(parent.getName())
                            .build())
                    .toList();

            List<AvailabilityResourceRefDto> components = membersByParent
                    .getOrDefault(resource.getId(), List.of())
                    .stream()
                    .map(member -> AvailabilityResourceRefDto.builder()
                            .tenantId(member.getTenantId())
                            .id(member.getId())
                            .code(member.getCode())
                            .name(member.getName())
                            .build())
                    .toList();

            List<AvailabilitySlotDto> slotDtos = freeSlots.stream()
                    .map(slot -> AvailabilitySlotDto.builder()
                            .start(slot.start.toLocalTime())
                            .end(slot.end.toLocalTime())
                            .build())
                    .toList();

            List<AvailabilitySlotDto> gridSlots = buildGridSlots(
                    window,
                    freeSlots,
                    gridMinutesByResourceId.get(resource.getId())
            );

            List<AvailabilityPriceDto> priceDtos = buildPrices(resource, productsByResourceId, pricesByProductId, productNameById, uomNameByCode);

            AvailabilityMapPositionDto mapPosition = null;
            ResourceMapResource mapResource = mapByResourceId.get(resource.getId());
            if (mapResource != null && mapResource.getResourceMap() != null) {
                ResourceMap map = mapResource.getResourceMap();
                mapPosition = AvailabilityMapPositionDto.builder()
                        .tenantId(map.getTenantId())
                        .mapId(map.getId())
                        .polygon(mapResource.getPolygon())
                        .label(mapResource.getLabel())
                        .build();
            }

            AvailabilityResourceDto dto = AvailabilityResourceDto.builder()
                    .tenantId(resource.getTenantId())
                    .id(resource.getId())
                    .code(resource.getCode())
                    .name(resource.getName())
                    .typeCode(resource.getResourceType() != null ? resource.getResourceType().getCode() : null)
                    .locationId(resource.getLocation() != null ? resource.getLocation().getId() : null)
                    .status(status)
                    .canBookAlone(resource.getCanBookAlone())
                    .colorHex(resource.getColorHex())
                    .displayOrder(resource.getDisplayOrder())
                    .serviceWindowStart(window != null ? window.open().toLocalTime() : null)
                    .serviceWindowEnd(window != null ? window.close().toLocalTime() : null)
                    .availableSlots(slotDtos)
                    .gridSlots(gridSlots)
                    .prices(priceDtos)
                    .parents(parents)
                    .components(components)
                    .map(mapPosition)
                    .build();

            resourceDtos.add(dto);
        }

        List<AvailabilityMapDto> mapDtos = maps.stream()
                .map(map -> AvailabilityMapDto.builder()
                        .tenantId(map.getTenantId())
                        .mapId(map.getId())
                        .locationNodeId(map.getLocationNode() != null ? map.getLocationNode().getId() : null)
                        .name(map.getName())
                        .imageUrl(map.getImageUrl())
                        .svgOverlay(map.getSvgOverlay())
                        .build())
                .toList();

        Integer gridMinutes = gridMinutesByLocation.values().stream().filter(Objects::nonNull).findFirst().orElse(null);
        return AvailabilityResponse.builder()
                .tenantId(tenantId)
                .date(date)
                .gridMinutes(gridMinutes)
                .maps(mapDtos)
                .resources(resourceDtos)
                .build();
    }

    private List<AvailabilityPriceDto> buildPrices(Resource resource,
                                                   Map<Long, List<Product>> productsByResourceId,
                                                   Map<Long, List<PriceListEntry>> pricesByProductId,
                                                   Map<Long, String> productNameById,
                                                   Map<String, String> uomNameByCode) {
        List<Product> products;
        if (resource.getProduct() != null && resource.getProduct().getId() != null) {
            products = List.of(resource.getProduct());
        } else {
            products = productsByResourceId.getOrDefault(resource.getId(), List.of());
        }
        List<AvailabilityPriceDto> prices = new ArrayList<>();
        for (Product product : products) {
            List<PriceListEntry> entries = pricesByProductId.getOrDefault(product.getId(), List.of());
            for (PriceListEntry entry : entries) {
                if (entry.getPriceProfile() == null || entry.getPriceProfileDate() == null) {
                    continue;
                }
                prices.add(AvailabilityPriceDto.builder()
                        .tenantId(product.getTenantId())
                        .productId(product.getId())
                        .productName(productNameById.get(product.getId()))
                        .uom(entry.getUom())
                        .uomName(entry.getUom() != null ? uomNameByCode.get(entry.getUom().toUpperCase()) : null)
                        .startTime(entry.getStartTime())
                        .endTime(entry.getEndTime())
                        .amount(entry.getPrice())
                        .currency(entry.getPriceProfile().getCurrency())
                        .priceProfileId(entry.getPriceProfile().getId())
                        .priceProfileDateId(entry.getPriceProfileDate().getId())
                        .build());
            }
        }
        return prices;
    }

    private List<Interval> computeFreeSlots(ServiceCalendarService.ServiceWindow window, List<Allocation> allocations) {
        if (window == null) {
            return List.of();
        }
        List<Interval> busy = new ArrayList<>();
        for (Allocation allocation : allocations) {
            LocalDateTime start = max(allocation.getStartsAt(), window.open());
            LocalDateTime end = min(allocation.getEndsAt(), window.close());
            if (end.isAfter(start)) {
                busy.add(new Interval(start, end));
            }
        }

        if (busy.isEmpty()) {
            return List.of(new Interval(window.open(), window.close()));
        }

        busy.sort(Comparator.comparing(a -> a.start));
        List<Interval> merged = new ArrayList<>();
        Interval current = busy.get(0);
        for (int i = 1; i < busy.size(); i++) {
            Interval next = busy.get(i);
            if (!next.start.isAfter(current.end)) {
                current = new Interval(current.start, max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        List<Interval> free = new ArrayList<>();
        LocalDateTime cursor = window.open();
        for (Interval interval : merged) {
            if (interval.start.isAfter(cursor)) {
                free.add(new Interval(cursor, interval.start));
            }
            cursor = max(cursor, interval.end);
        }
        if (cursor.isBefore(window.close())) {
            free.add(new Interval(cursor, window.close()));
        }
        return free;
    }

    private Map<Long, List<Interval>> computeEffectiveSlots(List<Resource> resources,
                                                            Map<Long, List<Interval>> freeSlotsByResourceId,
                                                            Map<Long, List<Resource>> membersByParent,
                                                            Map<Long, List<Resource>> parentsByMember) {
        Map<Long, List<Interval>> effective = new HashMap<>();
        for (Resource resource : resources) {
            effective.put(resource.getId(), freeSlotsByResourceId.getOrDefault(resource.getId(), List.of()));
        }

        boolean changed;
        do {
            changed = false;
            for (Resource resource : resources) {
                Long id = resource.getId();
                List<Interval> current = effective.getOrDefault(id, List.of());
                List<Interval> next = current;

                List<Resource> members = membersByParent.getOrDefault(id, List.of());
                if (!members.isEmpty()) {
                    List<Interval> memberIntersection = null;
                    for (Resource member : members) {
                        List<Interval> memberSlots = effective.getOrDefault(member.getId(), List.of());
                        if (memberIntersection == null) {
                            memberIntersection = new ArrayList<>(memberSlots);
                        } else {
                            memberIntersection = intersectIntervals(memberIntersection, memberSlots);
                        }
                        if (memberIntersection.isEmpty()) {
                            break;
                        }
                    }
                    if (memberIntersection != null) {
                        next = intersectIntervals(next, memberIntersection);
                    }
                }

                List<Resource> parents = parentsByMember.getOrDefault(id, List.of());
                if (!parents.isEmpty()) {
                    List<Interval> parentUnion = new ArrayList<>();
                    for (Resource parent : parents) {
                        parentUnion = unionIntervals(parentUnion, effective.getOrDefault(parent.getId(), List.of()));
                    }
                    next = intersectIntervals(next, parentUnion);
                }

                if (!sameIntervals(current, next)) {
                    effective.put(id, next);
                    changed = true;
                }
            }
        } while (changed);

        return effective;
    }

    private boolean sameIntervals(List<Interval> left, List<Interval> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            Interval a = left.get(i);
            Interval b = right.get(i);
            if (!Objects.equals(a.start, b.start) || !Objects.equals(a.end, b.end)) {
                return false;
            }
        }
        return true;
    }

    private List<Interval> intersectIntervals(List<Interval> left, List<Interval> right) {
        List<Interval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        List<Interval> leftSorted = left.stream().sorted(Comparator.comparing(a -> a.start)).toList();
        List<Interval> rightSorted = right.stream().sorted(Comparator.comparing(a -> a.start)).toList();
        while (i < leftSorted.size() && j < rightSorted.size()) {
            Interval a = leftSorted.get(i);
            Interval b = rightSorted.get(j);
            LocalDateTime start = max(a.start, b.start);
            LocalDateTime end = min(a.end, b.end);
            if (end.isAfter(start)) {
                result.add(new Interval(start, end));
            }
            if (a.end.isBefore(b.end)) {
                i++;
            } else {
                j++;
            }
        }
        return result;
    }

    private List<Interval> unionIntervals(List<Interval> left, List<Interval> right) {
        if (left.isEmpty()) {
            return new ArrayList<>(right);
        }
        if (right.isEmpty()) {
            return new ArrayList<>(left);
        }
        List<Interval> all = new ArrayList<>(left.size() + right.size());
        all.addAll(left);
        all.addAll(right);
        all.sort(Comparator.comparing(a -> a.start));

        List<Interval> merged = new ArrayList<>();
        Interval current = all.get(0);
        for (int i = 1; i < all.size(); i++) {
            Interval next = all.get(i);
            if (!next.start.isAfter(current.end)) {
                current = new Interval(current.start, max(current.end, next.end));
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private String statusFromFreeSlots(ServiceCalendarService.ServiceWindow window, List<Interval> freeSlots) {
        if (window == null || freeSlots.isEmpty()) {
            return STATUS_UNAVAILABLE;
        }
        if (freeSlots.size() == 1) {
            Interval only = freeSlots.get(0);
            if (Objects.equals(only.start, window.open()) && Objects.equals(only.end, window.close())) {
                return STATUS_AVAILABLE;
            }
        }
        return STATUS_PARTIAL;
    }

    private LocalDateTime max(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }

    private LocalDateTime min(LocalDateTime a, LocalDateTime b) {
        return a.isBefore(b) ? a : b;
    }

    private List<AvailabilitySlotDto> buildGridSlots(ServiceCalendarService.ServiceWindow window,
                                                     List<Interval> freeSlots,
                                                     Integer gridMinutes) {
        if (gridMinutes == null || gridMinutes <= 0 || window == null) {
            return List.of();
        }
        List<AvailabilitySlotDto> gridSlots = new ArrayList<>();
        LocalDateTime cursor = window.open();
        while (cursor.plusMinutes(gridMinutes).compareTo(window.close()) <= 0) {
            LocalDateTime end = cursor.plusMinutes(gridMinutes);
            boolean available = isSlotAvailable(cursor, end, freeSlots);
            gridSlots.add(AvailabilitySlotDto.builder()
                    .start(cursor.toLocalTime())
                    .end(end.toLocalTime())
                    .gridSlotStatus(available ? STATUS_AVAILABLE : STATUS_UNAVAILABLE)
                    .build());
            cursor = end;
        }
        return gridSlots;
    }

    private boolean isSlotAvailable(LocalDateTime start, LocalDateTime end, List<Interval> freeSlots) {
        for (Interval slot : freeSlots) {
            if ((start.equals(slot.start) || start.isAfter(slot.start))
                    && (end.equals(slot.end) || end.isBefore(slot.end))) {
                return true;
            }
        }
        return false;
    }

    private record Interval(LocalDateTime start, LocalDateTime end) {}
}
