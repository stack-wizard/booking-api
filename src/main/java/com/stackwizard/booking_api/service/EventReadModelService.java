package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class EventReadModelService {
    private final EventService eventService;
    private final EventRepository eventRepo;
    private final EventFunctionRepository functionRepo;
    private final EventFunctionItemRepository itemRepo;
    private final EventReservationSync reservationSync;
    private final ResourceRepository resourceRepo;
    private final AllocationRepository allocationRepo;
    private final CrmAccessContext accessContext;

    public EventReadModelService(EventService eventService,
                                 EventRepository eventRepo,
                                 EventFunctionRepository functionRepo,
                                 EventFunctionItemRepository itemRepo,
                                 EventReservationSync reservationSync,
                                 ResourceRepository resourceRepo,
                                 AllocationRepository allocationRepo,
                                 CrmAccessContext accessContext) {
        this.eventService = eventService;
        this.eventRepo = eventRepo;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.reservationSync = reservationSync;
        this.resourceRepo = resourceRepo;
        this.allocationRepo = allocationRepo;
        this.accessContext = accessContext;
    }

    @Transactional(readOnly = true)
    public EventDtos.Financials financials(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        List<EventFunction> functions = functionRepo.findForEvent(
                event.getTenantId(), event.getId());
        List<Long> functionIds = functions.stream().map(EventFunction::getId).toList();
        Map<Long, Reservation> rentByFunction = reservationSync.activeLines(functions).stream()
                .collect(Collectors.toMap(Reservation::getEventFunctionId, Function.identity(), (a, b) -> a));
        Map<Long, List<EventFunctionItem>> itemsByFunction = functionIds.isEmpty() ? Map.of()
                : itemRepo.findForFunctions(event.getTenantId(), functionIds).stream()
                .collect(Collectors.groupingBy(EventFunctionItem::getEventFunctionId));
        Map<Long, String> resourceNames = resourceNames(functions.stream().map(EventFunction::getResourceId).toList());

        BigDecimal rentTotal = BigDecimal.ZERO;
        BigDecimal itemsTotal = BigDecimal.ZERO;
        BigDecimal costTotal = BigDecimal.ZERO;
        List<EventDtos.FunctionFinancials> rows = new ArrayList<>();
        for (EventFunction function : functions) {
            Reservation rent = rentByFunction.get(function.getId());
            BigDecimal rentAmount = rent != null && rent.getGrossAmount() != null ? rent.getGrossAmount() : BigDecimal.ZERO;
            List<EventFunctionItem> items = itemsByFunction.getOrDefault(function.getId(), List.of());
            BigDecimal itemAmount = items.stream().map(EventFunctionItem::getGrossAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal itemCost = items.stream()
                    .map(i -> i.getCostAmount() != null ? i.getCostAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            rentTotal = rentTotal.add(rentAmount);
            itemsTotal = itemsTotal.add(itemAmount);
            costTotal = costTotal.add(itemCost);
            rows.add(EventDtos.FunctionFinancials.builder()
                    .functionId(function.getId())
                    .name(function.getName())
                    .functionType(function.getFunctionType().name())
                    .resourceId(function.getResourceId())
                    .resourceName(resourceNames.get(function.getResourceId()))
                    .rentReservationId(rent != null ? rent.getId() : null)
                    .rentStatus(rent != null ? rent.getStatus() : null)
                    .rentUom(rent != null ? rent.getUom() : null)
                    .rentQty(rent != null ? rent.getQty() : null)
                    .rentUnitPrice(rent != null ? rent.getUnitPrice() : null)
                    .rentAmount(money(rentAmount))
                    .itemsAmount(money(itemAmount))
                    .costAmount(money(itemCost))
                    .build());
        }
        BigDecimal total = rentTotal.add(itemsTotal);
        return EventDtos.Financials.builder()
                .currency(event.getCurrency())
                .rentTotal(money(rentTotal))
                .itemsTotal(money(itemsTotal))
                .total(money(total))
                .costTotal(money(costTotal))
                .margin(money(total.subtract(costTotal)))
                .billablePax(EventItemPricing.billablePax(event))
                .functions(rows)
                .build();
    }

    /**
     * Function-room occupancy for the date range: active allocations (event lines and ordinary
     * reservations) plus INQUIRY functions that do not hold the space yet.
     */
    @Transactional(readOnly = true)
    public List<EventDtos.SpaceGridRow> spaceGrid(LocalDate from, LocalDate to, Long locationId) {
        accessContext.require(CrmPermission.EVENT_READ);
        if (from == null || to == null || to.isBefore(from)) {
            throw new IllegalArgumentException("from and to are required and to must not be before from");
        }
        if (from.plusDays(62).isBefore(to)) {
            throw new IllegalArgumentException("Range is limited to 62 days");
        }
        Long tenantId = TenantResolver.requireTenantId();
        Long orgTenantId = TenantResolver.requireOrgTenantId();
        LocalDateTime start = from.atStartOfDay();
        LocalDateTime end = to.plusDays(1).atStartOfDay();
        List<Resource> spaces = (locationId != null
                ? resourceRepo.findByTenantIdAndLocationId(tenantId, locationId)
                : resourceRepo.findByTenantId(tenantId)).stream()
                .filter(r -> r.getResourceType() != null && r.getResourceType().getCode() != null
                        && ResourceSetupCapacityService.SPACE_RESOURCE_TYPES.contains(
                        r.getResourceType().getCode().toUpperCase(Locale.ROOT)))
                .sorted(Comparator.comparing(Resource::getDisplayOrder, Comparator.nullsLast(Integer::compareTo))
                        .thenComparing(Resource::getName))
                .toList();
        if (spaces.isEmpty()) {
            return List.of();
        }
        List<Long> spaceIds = spaces.stream().map(Resource::getId).toList();
        Map<Long, List<EventDtos.SpaceGridBooking>> bookings = new HashMap<>();

        List<Allocation> allocations = allocationRepo.findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan(
                spaceIds, end, start);
        Set<Long> functionIds = new HashSet<>();
        for (Allocation allocation : allocations) {
            if (allocation.getReservation().getEventFunctionId() != null) {
                functionIds.add(allocation.getReservation().getEventFunctionId());
            }
        }
        List<EventFunction> planned = functionRepo
                .findByTenantIdAndResourceIdIsNotNullAndOccupancyStartsAtLessThanAndOccupancyEndsAtGreaterThan(tenantId, end, start);
        planned.forEach(f -> functionIds.add(f.getId()));
        Map<Long, EventFunction> functionsById = functionIds.isEmpty() ? Map.of()
                : functionRepo.findByTenantIdAndIdIn(tenantId, functionIds).stream()
                .collect(Collectors.toMap(EventFunction::getId, Function.identity()));
        Set<Long> eventIds = functionsById.values().stream().map(EventFunction::getEventId).collect(Collectors.toSet());
        Map<Long, Event> eventsById = eventIds.isEmpty() ? Map.of()
                : eventRepo.findAllById(eventIds).stream()
                .filter(e -> orgTenantId.equals(e.getTenantId()))
                .collect(Collectors.toMap(Event::getId, Function.identity()));

        Set<Long> functionsWithAllocation = new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (Allocation allocation : allocations) {
            Reservation reservation = allocation.getReservation();
            EventFunction function = reservation.getEventFunctionId() != null ? functionsById.get(reservation.getEventFunctionId()) : null;
            Event event = function != null ? eventsById.get(function.getEventId()) : null;
            if (function != null) {
                functionsWithAllocation.add(function.getId());
            }
            List<Long> rowIds = new ArrayList<>();
            rowIds.add(allocation.getAllocatedResource().getId());
            if (Boolean.TRUE.equals(allocation.getCompositResource()) && allocation.getCompositResourceId() != null) {
                rowIds.add(allocation.getCompositResourceId());
            }
            for (Long rowId : rowIds) {
                if (!seen.add(rowId + ":" + reservation.getId())) {
                    continue;
                }
                bookings.computeIfAbsent(rowId, k -> new ArrayList<>())
                    .add(EventDtos.SpaceGridBooking.builder()
                            .kind(function != null ? "EVENT" : "RESERVATION")
                            .status(allocation.getStatus())
                            .startsAt(allocation.getStartsAt())
                            .endsAt(allocation.getEndsAt())
                            .eventId(event != null ? event.getId() : null)
                            .eventName(event != null ? event.getName() : reservation.getCustomerName())
                            .eventStatus(event != null ? event.getStatus().name() : null)
                            .functionId(function != null ? function.getId() : null)
                            .functionName(function != null ? functionLabel(function) : null)
                            .reservationRequestId(reservation.getRequest() != null ? reservation.getRequest().getId() : null)
                            .build());
            }
        }
        for (EventFunction function : planned) {
            Event event = eventsById.get(function.getEventId());
            if (event == null || functionsWithAllocation.contains(function.getId()) || event.getStatus() != Event.Status.INQUIRY) {
                continue;
            }
            bookings.computeIfAbsent(function.getResourceId(), k -> new ArrayList<>())
                    .add(EventDtos.SpaceGridBooking.builder()
                            .kind("PLANNED")
                            .status("INQUIRY")
                            .startsAt(function.getOccupancyStartsAt())
                            .endsAt(function.getOccupancyEndsAt())
                            .eventId(event.getId())
                            .eventName(event.getName())
                            .eventStatus(event.getStatus().name())
                            .functionId(function.getId())
                            .functionName(functionLabel(function))
                            .build());
        }

        List<EventDtos.SpaceGridRow> rows = new ArrayList<>();
        for (Resource space : spaces) {
            List<EventDtos.SpaceGridBooking> list = bookings.getOrDefault(space.getId(), new ArrayList<>());
            list.sort(Comparator.comparing(EventDtos.SpaceGridBooking::getStartsAt));
            rows.add(EventDtos.SpaceGridRow.builder()
                    .resourceId(space.getId())
                    .resourceName(space.getName())
                    .resourceType(space.getResourceType().getCode())
                    .bookings(list)
                    .build());
        }
        return rows;
    }

    private Map<Long, String> resourceNames(List<Long> ids) {
        List<Long> nonNull = ids.stream().filter(id -> id != null).distinct().toList();
        if (nonNull.isEmpty()) {
            return Map.of();
        }
        return resourceRepo.findAllById(nonNull).stream().collect(Collectors.toMap(Resource::getId, Resource::getName));
    }

    private static String functionLabel(EventFunction function) {
        return function.getName() != null ? function.getName() : function.getFunctionType().name();
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
