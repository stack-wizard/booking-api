package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.booking.BookingTranslationService;
import com.stackwizard.booking_api.dto.BookingRequest;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.Resource;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ResourceCompositionRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceInternalRequestTest {

    @Mock
    private ReservationRepository reservationRepo;
    @Mock
    private AllocationRepository allocationRepo;
    @Mock
    private ResourceRepository resourceRepo;
    @Mock
    private ProductRepository productRepo;
    @Mock
    private PriceListEntryRepository priceListRepo;
    @Mock
    private ResourceCompositionRepository compositionRepo;
    @Mock
    private BookingTranslationService translationService;
    @Mock
    private ReservationRequestRepository requestRepo;
    @Mock
    private TenantConfigService tenantConfigService;
    @Mock
    private ReservationRequestAccessTokenService accessTokenService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(
                reservationRepo,
                allocationRepo,
                resourceRepo,
                productRepo,
                priceListRepo,
                compositionRepo,
                translationService,
                requestRepo,
                tenantConfigService,
                accessTokenService,
                eventPublisher
        );
    }

    @Test
    void createBookingLeavesInternalDraftRequestOpenEnded() {
        Long tenantId = 7L;
        LocalDate serviceDate = LocalDate.of(2026, 6, 15);
        LocalDateTime startsAt = LocalDateTime.of(2026, 6, 15, 9, 0);
        LocalDateTime endsAt = LocalDateTime.of(2026, 6, 15, 18, 0);

        Product product = Product.builder()
                .id(11L)
                .defaultUom("DAY")
                .extraUoms(Set.of())
                .build();
        Resource resource = Resource.builder()
                .id(21L)
                .tenantId(tenantId)
                .kind("EXACT")
                .unitCount(1)
                .product(product)
                .build();
        PriceListEntry priceListEntry = PriceListEntry.builder()
                .productId(product.getId())
                .uom("DAY")
                .price(new BigDecimal("150.00"))
                .build();

        BookingRequest bookingRequest = new BookingRequest();
        bookingRequest.setTenantId(tenantId);
        bookingRequest.setResourceId(resource.getId());
        bookingRequest.setRequestType("internal");
        bookingRequest.setCurrency("EUR");
        bookingRequest.setUom("DAY");
        bookingRequest.setServiceDate(serviceDate);

        when(resourceRepo.findById(resource.getId())).thenReturn(Optional.of(resource));
        when(priceListRepo.findForProductUomOnDate(product.getId(), "DAY", "EUR", tenantId, serviceDate))
                .thenReturn(List.of(priceListEntry));
        when(translationService.translate("DAY", 1, tenantId, null, serviceDate, null))
                .thenReturn(new BookingTranslationService.TranslatedPeriod(startsAt, endsAt));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> {
            ReservationRequest request = invocation.getArgument(0);
            request.setId(99L);
            return request;
        });
        when(reservationRepo.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(101L);
            return reservation;
        });
        when(compositionRepo.findByParentResourceId(resource.getId())).thenReturn(List.of());
        when(allocationRepo.countActiveByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(
                resource.getId(), endsAt, startsAt)).thenReturn(0L);
        when(allocationRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Allocation> allocations = service.createBooking(bookingRequest);

        assertThat(allocations).hasSize(1);
        Allocation allocation = allocations.getFirst();
        assertThat(allocation.getExpiresAt()).isNull();
        assertThat(allocation.getReservation().getExpiresAt()).isNull();
        assertThat(allocation.getReservation().getRequestType()).isEqualTo(ReservationRequest.Type.INTERNAL);
        assertThat(allocation.getReservation().getRequest().getExpiresAt()).isNull();

        ArgumentCaptor<ReservationRequest> requestCaptor = ArgumentCaptor.forClass(ReservationRequest.class);
        verify(requestRepo).save(requestCaptor.capture());
        assertThat(requestCaptor.getValue().getType()).isEqualTo(ReservationRequest.Type.INTERNAL);
        assertThat(requestCaptor.getValue().getExpiresAt()).isNull();
    }

    @Test
    void saveHoldReservationRepairsLegacyInternalExpiryToOpenEnded() {
        Long tenantId = 7L;
        OffsetDateTime expiredAt = OffsetDateTime.now().minusDays(2);

        ReservationRequest existingRequest = ReservationRequest.builder()
                .id(55L)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.INTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .expiresAt(expiredAt)
                .extensionCount(1)
                .build();
        Reservation existingReservation = Reservation.builder()
                .id(77L)
                .tenantId(tenantId)
                .status("HOLD")
                .expiresAt(expiredAt)
                .build();
        Allocation existingAllocation = Allocation.builder()
                .id(88L)
                .expiresAt(expiredAt)
                .build();
        Reservation reservationToSave = Reservation.builder()
                .tenantId(tenantId)
                .request(ReservationRequest.builder().id(existingRequest.getId()).build())
                .build();

        when(requestRepo.findById(existingRequest.getId())).thenReturn(Optional.of(existingRequest));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepo.findByRequestId(existingRequest.getId())).thenReturn(List.of(existingReservation));
        when(reservationRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.findByReservationIdIn(List.of(existingReservation.getId()))).thenReturn(List.of(existingAllocation));
        when(allocationRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepo.save(any(Reservation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Reservation saved = service.saveHoldReservation(reservationToSave);

        assertThat(saved.getExpiresAt()).isNull();
        assertThat(saved.getRequest().getExpiresAt()).isNull();
        assertThat(existingReservation.getExpiresAt()).isNull();
        assertThat(existingAllocation.getExpiresAt()).isNull();
    }

    @Test
    void createReservationAndAllocateUsesNullExpiryForExistingInternalDraftRequest() {
        Long tenantId = 1L;
        OffsetDateTime futureExpiry = OffsetDateTime.now().plusMinutes(15);
        LocalDateTime startsAt = LocalDateTime.of(2026, 5, 17, 18, 31);
        LocalDateTime endsAt = LocalDateTime.of(2026, 5, 17, 19, 31);

        ReservationRequest existingRequest = ReservationRequest.builder()
                .id(118L)
                .tenantId(tenantId)
                .type(ReservationRequest.Type.INTERNAL)
                .status(ReservationRequest.Status.DRAFT)
                .expiresAt(futureExpiry)
                .extensionCount(0)
                .build();
        Resource requestedResource = Resource.builder()
                .id(211L)
                .tenantId(tenantId)
                .kind("EXACT")
                .unitCount(1)
                .build();
        Reservation incoming = Reservation.builder()
                .tenantId(tenantId)
                .request(ReservationRequest.builder().id(existingRequest.getId()).build())
                .requestType(ReservationRequest.Type.INTERNAL)
                .requestedResource(Resource.builder().id(requestedResource.getId()).build())
                .startsAt(startsAt)
                .endsAt(endsAt)
                .build();
        Reservation linkedReservation = Reservation.builder()
                .id(4177L)
                .tenantId(tenantId)
                .status("HOLD")
                .expiresAt(futureExpiry)
                .build();

        when(resourceRepo.findById(requestedResource.getId())).thenReturn(Optional.of(requestedResource));
        when(requestRepo.findById(existingRequest.getId())).thenReturn(Optional.of(existingRequest));
        when(requestRepo.save(any(ReservationRequest.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(reservationRepo.findByRequestId(existingRequest.getId())).thenReturn(List.of(linkedReservation));
        when(reservationRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));
        when(allocationRepo.findByReservationIdIn(List.of(linkedReservation.getId()))).thenReturn(List.of());
        when(allocationRepo.countActiveByAllocatedResourceIdAndStartsAtLessThanAndEndsAtGreaterThan(
                requestedResource.getId(), endsAt, startsAt)).thenReturn(0L);
        when(reservationRepo.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation reservation = invocation.getArgument(0);
            reservation.setId(4178L);
            return reservation;
        });
        when(compositionRepo.findByParentResourceId(requestedResource.getId())).thenReturn(List.of());
        when(allocationRepo.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<Allocation> allocations = service.createReservationAndAllocate(incoming);

        assertThat(allocations).hasSize(1);
        Reservation savedReservation = allocations.getFirst().getReservation();
        assertThat(savedReservation.getRequest().getExpiresAt()).isNull();
        assertThat(savedReservation.getExpiresAt()).isNull();
        assertThat(savedReservation.getRequestType()).isEqualTo(ReservationRequest.Type.INTERNAL);
    }
}
