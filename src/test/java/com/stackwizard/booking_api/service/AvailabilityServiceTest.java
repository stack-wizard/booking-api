package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.AvailabilityResponse;
import com.stackwizard.booking_api.dto.AvailabilityResourceDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.BookingCalendar;
import com.stackwizard.booking_api.model.CancellationPolicy;
import com.stackwizard.booking_api.model.LocationNode;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.model.ResourceComposition;
import com.stackwizard.booking_api.model.ResourceType;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceMapRepository;
import com.stackwizard.booking_api.repository.ResourceMapResourceRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import com.stackwizard.booking_api.repository.UomRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AvailabilityServiceTest {

    @Test
    void returnsTopLevelTenantCancellationPolicyForRequestedPeriod() {
        Long tenantId = 1L;
        Long locationId = 9L;
        LocalDate date = LocalDate.of(2031, 6, 10);
        LocalDateTime open = LocalDateTime.of(date, LocalTime.of(10, 0));
        LocalDateTime close = LocalDateTime.of(date, LocalTime.of(19, 0));

        LocationNode location = LocationNode.builder()
                .id(locationId)
                .tenantId(tenantId)
                .name("Beach Club")
                .nodeType("AREA")
                .sortOrder(1)
                .build();
        ResourceType exactType = ResourceType.builder()
                .id(2L)
                .tenantId(tenantId)
                .code("EXACT")
                .name("Exact")
                .build();
        Resource sunbed = resource(194L, tenantId, "L3", "Luxury Sunbed 3", true, exactType, location, 1);

        BookingCalendar calendar = BookingCalendar.builder()
                .id(1L)
                .tenantId(tenantId)
                .locationNode(location)
                .openTime(open.toLocalTime())
                .closeTime(close.toLocalTime())
                .gridMinutes(60)
                .minDurationMinutes(60)
                .maxDurationMinutes(540)
                .zone("Europe/Zagreb")
                .build();

        CancellationPolicy tenantPolicy = CancellationPolicy.builder()
                .id(55L)
                .tenantId(tenantId)
                .name("Future tenant policy")
                .active(true)
                .priority(100)
                .scopeType("TENANT")
                .cutoffDaysBeforeStart(5)
                .beforeCutoffReleaseType("FULL")
                .beforeCutoffReleaseValue(java.math.BigDecimal.ZERO)
                .beforeCutoffAllowCashRefund(true)
                .beforeCutoffAllowCustomerCredit(false)
                .beforeCutoffDefaultSettlementMode("CASH_REFUND")
                .afterCutoffReleaseType("NONE")
                .afterCutoffReleaseValue(java.math.BigDecimal.ZERO)
                .afterCutoffAllowCashRefund(false)
                .afterCutoffAllowCustomerCredit(false)
                .effectiveFrom(OffsetDateTime.parse("2030-01-01T00:00:00Z"))
                .build();

        ResourceRepository resourceRepo = stub(ResourceRepository.class, Map.of(
                "findByTenantIdAndLocationId", args -> List.of(sunbed)
        ));
        ResourceCompositionRepository compositionRepo = stub(ResourceCompositionRepository.class, Map.of(
                "findByParentResourceIdIn", args -> List.of(),
                "findByMemberResourceIdIn", args -> List.of()
        ));
        AllocationRepository allocationRepo = stub(AllocationRepository.class, Map.of(
                "findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan", args -> List.of()
        ));
        ProductRepository productRepo = stub(ProductRepository.class, Map.of(
                "findByTenantIdOrderByDisplayOrderAscNameAscIdAsc", args -> List.of()
        ));
        PriceListEntryRepository priceListRepo = stub(PriceListEntryRepository.class, Map.of(
                "findForProductsOnDate", args -> List.of()
        ));
        ResourceMapRepository mapRepo = stub(ResourceMapRepository.class, Map.of(
                "findByTenantId", args -> List.of()
        ));
        ResourceMapResourceRepository mapResourceRepo = stub(ResourceMapResourceRepository.class, Map.of(
                "findByResourceMapIdIn", args -> List.of()
        ));
        UomRepository uomRepo = stub(UomRepository.class, Map.of(
                "findByActiveTrue", args -> List.of()
        ));
        CancellationPolicyService cancellationPolicyService = new CancellationPolicyService(
                stub(com.stackwizard.booking_api.repository.CancellationPolicyRepository.class, Map.of(
                        "findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc", args -> List.of(tenantPolicy)
                ))
        );

        ServiceCalendarService calendarService = new ServiceCalendarService(null, null) {
            @Override
            public BookingCalendar calendarFor(Long requestedTenantId, Long requestedLocationNodeId) {
                assertThat(requestedTenantId).isEqualTo(tenantId);
                assertThat(requestedLocationNodeId).isEqualTo(locationId);
                return calendar;
            }

            @Override
            public ServiceWindow windowFor(BookingCalendar requestedCalendar, LocalDate requestedDate) {
                assertThat(requestedCalendar).isSameAs(calendar);
                assertThat(requestedDate).isEqualTo(date);
                return new ServiceWindow(open, close);
            }
        };

        AvailabilityService service = new AvailabilityService(
                resourceRepo,
                compositionRepo,
                allocationRepo,
                productRepo,
                priceListRepo,
                calendarService,
                mapRepo,
                mapResourceRepo,
                uomRepo,
                cancellationPolicyService
        );

        AvailabilityResponse response = service.getAvailability(tenantId, date, locationId);

        assertThat(response.getCancellationPolicy()).isNotNull();
        assertThat(response.getCancellationPolicy().getPolicyId()).isEqualTo(55L);
        assertThat(response.getCancellationPolicy().getCancellationPolicyText())
                .contains("Cancellation up to 5 days before start");
        assertThat(response.getCancellationPolicy().getCancellationFreeUntil()).isEqualTo(open.minusDays(5));
    }

    @Test
    void keepsStandaloneMemberAvailableWhenParentPackageIsBlockedBySiblingAllocation() {
        Long tenantId = 1L;
        Long locationId = 9L;
        LocalDate date = LocalDate.of(2026, 3, 17);
        LocalDateTime open = LocalDateTime.of(date, LocalTime.of(10, 0));
        LocalDateTime close = LocalDateTime.of(date, LocalTime.of(19, 0));

        LocationNode location = LocationNode.builder()
                .id(locationId)
                .tenantId(tenantId)
                .name("Beach Club")
                .nodeType("AREA")
                .sortOrder(1)
                .build();
        ResourceType compositionType = ResourceType.builder()
                .id(1L)
                .tenantId(tenantId)
                .code("COMPOSITION")
                .name("Composition")
                .build();
        ResourceType exactType = ResourceType.builder()
                .id(2L)
                .tenantId(tenantId)
                .code("EXACT")
                .name("Exact")
                .build();

        Resource packageResource = resource(241L, tenantId, "BLP3", "Bespoke Luxury Package 3", false, compositionType, location, 1);
        Resource luxurySunbed = resource(194L, tenantId, "L3", "Luxury Sunbed 3", true, compositionType, location, 2);
        Resource peninsula = resource(301L, tenantId, "P3", "Peninsula 3", true, exactType, location, 3);

        List<Resource> resources = List.of(packageResource, luxurySunbed, peninsula);
        List<ResourceComposition> compositions = List.of(
                composition(packageResource, luxurySunbed),
                composition(packageResource, peninsula)
        );

        BookingCalendar calendar = BookingCalendar.builder()
                .id(1L)
                .tenantId(tenantId)
                .locationNode(location)
                .openTime(open.toLocalTime())
                .closeTime(close.toLocalTime())
                .gridMinutes(60)
                .minDurationMinutes(60)
                .maxDurationMinutes(540)
                .zone("Europe/Zagreb")
                .build();
        List<Allocation> activeAllocations = List.of(Allocation.builder()
                .id(1L)
                .tenantId(tenantId)
                .allocatedResource(peninsula)
                .requestedResource(peninsula)
                .resourceKind("EXACT")
                .compositResource(false)
                .startsAt(open)
                .endsAt(close)
                .status("CONFIRMED")
                .build());

        ResourceRepository resourceRepo = stub(ResourceRepository.class, Map.of(
                "findByTenantIdAndLocationId", args -> resources
        ));
        ResourceCompositionRepository compositionRepo = stub(ResourceCompositionRepository.class, Map.of(
                "findByParentResourceIdIn", args -> compositions,
                "findByMemberResourceIdIn", args -> compositions
        ));
        AllocationRepository allocationRepo = stub(AllocationRepository.class, Map.of(
                "findActiveByAllocatedResourceIdInAndStartsAtLessThanAndEndsAtGreaterThan", args -> activeAllocations
        ));
        ProductRepository productRepo = stub(ProductRepository.class, Map.of(
                "findByTenantIdOrderByDisplayOrderAscNameAscIdAsc", args -> List.of()
        ));
        PriceListEntryRepository priceListRepo = stub(PriceListEntryRepository.class, Map.of(
                "findForProductsOnDate", args -> List.of()
        ));
        ResourceMapRepository mapRepo = stub(ResourceMapRepository.class, Map.of(
                "findByTenantId", args -> List.of()
        ));
        ResourceMapResourceRepository mapResourceRepo = stub(ResourceMapResourceRepository.class, Map.of(
                "findByResourceMapIdIn", args -> List.of()
        ));
        UomRepository uomRepo = stub(UomRepository.class, Map.of(
                "findByActiveTrue", args -> List.of()
        ));
        CancellationPolicyService cancellationPolicyService = new CancellationPolicyService(
                stub(com.stackwizard.booking_api.repository.CancellationPolicyRepository.class, Map.of(
                        "findByTenantIdAndActiveTrueOrderByPriorityDescIdDesc", args -> List.of()
                ))
        );

        ServiceCalendarService calendarService = new ServiceCalendarService(null, null) {
            @Override
            public BookingCalendar calendarFor(Long requestedTenantId, Long requestedLocationNodeId) {
                assertThat(requestedTenantId).isEqualTo(tenantId);
                assertThat(requestedLocationNodeId).isEqualTo(locationId);
                return calendar;
            }

            @Override
            public ServiceWindow windowFor(BookingCalendar requestedCalendar, LocalDate requestedDate) {
                assertThat(requestedCalendar).isSameAs(calendar);
                assertThat(requestedDate).isEqualTo(date);
                return new ServiceWindow(open, close);
            }
        };

        AvailabilityService service = new AvailabilityService(
                resourceRepo,
                compositionRepo,
                allocationRepo,
                productRepo,
                priceListRepo,
                calendarService,
                mapRepo,
                mapResourceRepo,
                uomRepo,
                cancellationPolicyService
        );

        List<AvailabilityResourceDto> responseResources = service.getAvailability(tenantId, date, locationId).getResources();

        AvailabilityResourceDto luxuryDto = findResource(responseResources, "L3");
        AvailabilityResourceDto packageDto = findResource(responseResources, "BLP3");

        assertThat(luxuryDto.getStatus()).isEqualTo("AVAILABLE");
        assertThat(luxuryDto.getAvailableSlots())
                .singleElement()
                .satisfies(slot -> {
                    assertThat(slot.getStart()).isEqualTo(LocalTime.of(10, 0));
                    assertThat(slot.getEnd()).isEqualTo(LocalTime.of(19, 0));
                });
        assertThat(luxuryDto.getGridSlots())
                .hasSize(9)
                .allSatisfy(slot -> assertThat(slot.getGridSlotStatus()).isEqualTo("AVAILABLE"));

        assertThat(packageDto.getStatus()).isEqualTo("UNAVAILABLE");
        assertThat(packageDto.getAvailableSlots()).isEmpty();
        assertThat(packageDto.getGridSlots())
                .hasSize(9)
                .allSatisfy(slot -> assertThat(slot.getGridSlotStatus()).isEqualTo("UNAVAILABLE"));
    }

    private static AvailabilityResourceDto findResource(List<AvailabilityResourceDto> resources, String code) {
        return resources.stream()
                .filter(resource -> code.equals(resource.getCode()))
                .findFirst()
                .orElseThrow();
    }

    private static ResourceComposition composition(Resource parent, Resource member) {
        return ResourceComposition.builder()
                .tenantId(parent.getTenantId())
                .parentResource(parent)
                .memberResource(member)
                .qty(1)
                .build();
    }

    private static Resource resource(Long id,
                                     Long tenantId,
                                     String code,
                                     String name,
                                     boolean canBookAlone,
                                     ResourceType type,
                                     LocationNode location,
                                     int displayOrder) {
        return Resource.builder()
                .id(id)
                .tenantId(tenantId)
                .resourceType(type)
                .kind("EXACT")
                .location(location)
                .code(code)
                .name(name)
                .displayOrder(displayOrder)
                .status("ACTIVE")
                .canBookAlone(canBookAlone)
                .unitCount(1)
                .capAdults(2)
                .capChildren(0)
                .capInfants(0)
                .capTotal(2)
                .build();
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object handle(Object[] args);
    }

    private static <T> T stub(Class<T> type, Map<String, MethodHandler> handlers) {
        Object proxy = Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (instance, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> type.getSimpleName() + "Stub";
                            case "hashCode" -> System.identityHashCode(instance);
                            case "equals" -> instance == args[0];
                            default -> defaultValue(method.getReturnType());
                        };
                    }

                    MethodHandler handler = handlers.get(method.getName());
                    if (handler != null) {
                        return handler.handle(args != null ? args : new Object[0]);
                    }
                    return defaultValue(method.getReturnType());
                }
        );
        return type.cast(proxy);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (Optional.class.isAssignableFrom(returnType)) {
            return Optional.empty();
        }
        return null;
    }
}
