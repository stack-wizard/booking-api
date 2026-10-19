package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.BookingStatusFlow;
import com.stackwizard.booking_api.model.BookingStatusHistory;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.BookingStatusHistoryRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmScope;
import com.stackwizard.booking_api.security.TenantContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BookingServiceTest {

    @Mock BookingRepository bookingRepo;
    @Mock BookingStatusHistoryRepository historyRepo;
    @Mock BookingFunctionRepository functionRepo;
    @Mock CrmOutcomeReasonRepository outcomeRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmOpportunityRepository opportunityRepo;
    @Mock BookingReservationSync reservationSync;
    @Mock BookingItemPricing itemPricing;
    @Mock CrmAccessContext accessContext;
    @Mock CrmLookupService lookupService;
    @Mock CrmTeamDirectory teamDirectory;
    @Mock PlatformTransactionManager transactionManager;
    @Mock SalesQuoteRepository quoteRepo;
    @Mock BookingStatusService statusService;

    BookingService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        when(accessContext.scope()).thenReturn(CrmScope.ALL);
        when(accessContext.currentUserId()).thenReturn(7L);
        when(bookingRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(lookupService.requireCode(any(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(statusService.rules(any())).thenReturn(BookingStatusRules.defaults());
        service = new BookingService(bookingRepo, historyRepo, functionRepo, outcomeRepo, accountRepo, contactRepo,
                opportunityRepo, reservationSync, itemPricing, accessContext, teamDirectory, transactionManager, quoteRepo, TenantHierarchyTestSupport.standalone(), lookupService, statusService, true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Booking booking(Booking.Status status) {
        Booking booking = Booking.builder()
                .id(5L).tenantId(1L).accountId(2L).name("Pharma kongres")
                .status(status)
                .dateFrom(LocalDate.now().plusDays(30)).dateTo(LocalDate.now().plusDays(32))
                .currency("EUR")
                .build();
        when(bookingRepo.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(booking));
        return booking;
    }

    private BookingDtos.StatusChangeRequest to(Booking.Status status, Long reasonId) {
        BookingDtos.StatusChangeRequest request = new BookingDtos.StatusChangeRequest();
        request.setStatus(status);
        request.setOutcomeReasonId(reasonId);
        return request;
    }

    @Test
    void contentLockedFromDefinite() {
        assertThat(BookingService.isContentLocked(booking(Booking.Status.INQUIRY))).isFalse();
        assertThat(BookingService.isContentLocked(booking(Booking.Status.TENTATIVE))).isFalse();
        assertThat(BookingService.isContentLocked(booking(Booking.Status.DEFINITE))).isTrue();
        assertThat(BookingService.isContentLocked(booking(Booking.Status.ACTUAL))).isTrue();
    }

    @Test
    void requireContentEditableRejectsDefinite() {
        booking(Booking.Status.DEFINITE);
        assertThatThrownBy(() -> service.requireContentEditable(5L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEFINITE");
        assertThat(service.requireContentEditable(booking(Booking.Status.TENTATIVE).getId()).getStatus())
                .isEqualTo(Booking.Status.TENTATIVE);
    }

    @Test
    void inquiryCannotJumpToDefinite() {
        booking(Booking.Status.INQUIRY);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.DEFINITE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INQUIRY to DEFINITE");
        verify(reservationSync, never()).syncBooking(any());
    }

    @Test
    void definiteRequiresAcceptedQuote() {
        booking(Booking.Status.TENTATIVE).setDecisionDate(LocalDate.now().plusDays(3));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(true);
        when(quoteRepo.existsByTenantIdAndBookingIdAndStatus(1L, 5L, SalesQuote.Status.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.DEFINITE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepted quote");

        when(quoteRepo.existsByTenantIdAndBookingIdAndStatus(1L, 5L, SalesQuote.Status.ACCEPTED)).thenReturn(true);
        assertThat(service.changeStatus(5L, to(Booking.Status.DEFINITE, null)).getStatus()).isEqualTo(Booking.Status.DEFINITE);
    }

    @Test
    void tentativeRequiresDecisionDate() {
        booking(Booking.Status.INQUIRY);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.TENTATIVE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decisionDate");
    }

    @Test
    void tentativeRequiresAtLeastOneFunctionWithSpace() {
        Booking booking = booking(Booking.Status.INQUIRY);
        booking.setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.TENTATIVE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("space");
        verify(reservationSync, never()).syncBooking(any());
    }

    @Test
    void placedRoomBlockIsEnoughForTentative() {
        Booking booking = booking(Booking.Status.INQUIRY);
        booking.setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(false);
        when(reservationSync.hasPlacedRoomBlock(booking)).thenReturn(true);

        assertThat(service.changeStatus(5L, to(Booking.Status.TENTATIVE, null)).getStatus()).isEqualTo(Booking.Status.TENTATIVE);
        verify(reservationSync).syncBooking(booking);
    }

    @Test
    void tentativeHoldsSpaceAndWritesHistory() {
        Booking booking = booking(Booking.Status.INQUIRY);
        booking.setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(true);

        Booking saved = service.changeStatus(5L, to(Booking.Status.TENTATIVE, null));

        assertThat(saved.getStatus()).isEqualTo(Booking.Status.TENTATIVE);
        verify(reservationSync).syncBooking(booking);
        ArgumentCaptor<BookingStatusHistory> history = ArgumentCaptor.forClass(BookingStatusHistory.class);
        verify(historyRepo).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(Booking.Status.INQUIRY);
        assertThat(history.getValue().getToStatus()).isEqualTo(Booking.Status.TENTATIVE);
        assertThat(history.getValue().getChangedBy()).isEqualTo(7L);
    }

    @Test
    void lostRequiresReasonOfKindLost() {
        booking(Booking.Status.TENTATIVE);
        when(outcomeRepo.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(
                CrmOutcomeReason.builder().id(9L).tenantId(1L).kind(CrmOutcomeReason.Kind.TURNED_DOWN).build()));

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.LOST, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcomeReasonId");
        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.LOST, 9L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be LOST");
    }

    @Test
    void actualNeedsActualPax() {
        Booking booking = booking(Booking.Status.DEFINITE);
        booking.setDateFrom(LocalDate.now().minusDays(2));
        booking.setDateTo(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.changeStatus(5L, to(Booking.Status.ACTUAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actualPax");

        booking.setActualPax(118);
        Booking saved = service.changeStatus(5L, to(Booking.Status.ACTUAL, null));
        assertThat(saved.getStatus()).isEqualTo(Booking.Status.ACTUAL);
        verify(itemPricing).recalculatePerPax(booking);
    }

    @Test
    void closedBookingIsReadOnly() {
        booking(Booking.Status.LOST);

        assertThatThrownBy(() -> service.update(5L, Booking.builder().name("x").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOST");
    }

    @Test
    void guaranteedPaxCannotDropAfterDueDate() {
        Booking booking = booking(Booking.Status.TENTATIVE);
        booking.setGuaranteedPax(120);
        booking.setGuaranteeDueDate(LocalDate.now().minusDays(1));
        when(accountRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(new com.stackwizard.booking_api.model.CrmAccount()));

        Booking changes = Booking.builder().name("Pharma kongres").accountId(2L)
                .dateFrom(booking.getDateFrom()).dateTo(booking.getDateTo())
                .guaranteeDueDate(booking.getGuaranteeDueDate())
                .guaranteedPax(100).build();

        assertThatThrownBy(() -> service.update(5L, changes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reduced");
    }

    /** Defaults plus a second TENTATIVE status 8 "HOLD2"; INQUIRY may only go to HOLD2 (and close). */
    private void customRules() {
        java.util.List<BookingStatus> statuses = new java.util.ArrayList<>(BookingStatusRules.defaults().statuses());
        statuses.add(BookingStatus.builder().id(8L).code("HOLD2").label("2nd option").phase(Booking.Status.TENTATIVE)
                .start(false).displayOrder(25).active(true).build());
        java.util.List<BookingStatusFlow> flow = new java.util.ArrayList<>(BookingStatusRules.defaultFlow(statuses).stream()
                .filter(f -> f.getFromStatusId() != 1L || f.getToStatusId() != 2L)
                .toList());
        flow.add(BookingStatusFlow.builder().fromStatusId(8L).toStatusId(2L).build());
        when(statusService.rules(any())).thenReturn(new BookingStatusRules(statuses, flow));
    }

    private BookingDtos.StatusChangeRequest toCode(String code) {
        BookingDtos.StatusChangeRequest request = new BookingDtos.StatusChangeRequest();
        request.setStatusCode(code);
        return request;
    }

    @Test
    void configuredFlowLimitsMoves() {
        customRules();
        booking(Booking.Status.INQUIRY).setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(true);

        assertThatThrownBy(() -> service.changeStatus(5L, toCode("TENTATIVE")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INQUIRY to TENTATIVE");

        Booking saved = service.changeStatus(5L, toCode("hold2"));
        assertThat(saved.getStatus()).isEqualTo(Booking.Status.TENTATIVE);
        assertThat(saved.getStatusCode()).isEqualTo("HOLD2");
    }

    @Test
    void moveWithinPhaseSkipsPhaseChecksAndRecordsCodes() {
        customRules();
        Booking booking = booking(Booking.Status.TENTATIVE);
        booking.setStatusCode("HOLD2");

        Booking saved = service.changeStatus(5L, toCode("TENTATIVE"));

        assertThat(saved.getStatus()).isEqualTo(Booking.Status.TENTATIVE);
        assertThat(saved.getStatusCode()).isEqualTo("TENTATIVE");
        verify(reservationSync, never()).syncBooking(any());
        ArgumentCaptor<BookingStatusHistory> history = ArgumentCaptor.forClass(BookingStatusHistory.class);
        verify(historyRepo).save(history.capture());
        assertThat(history.getValue().getFromCode()).isEqualTo("HOLD2");
        assertThat(history.getValue().getToCode()).isEqualTo("TENTATIVE");
    }

    @Test
    void stageStatusMovesOpenBookingsAndNamesTheBlockingOne() {
        Booking open = booking(Booking.Status.INQUIRY);
        open.setDecisionDate(LocalDate.now().plusDays(7));
        Booking closed = Booking.builder().id(6L).tenantId(1L).name("Closed").status(Booking.Status.LOST).build();
        when(bookingRepo.findByTenantIdAndOpportunityIdOrderByDateFromAsc(1L, 3L)).thenReturn(java.util.List.of(closed, open));
        when(functionRepo.existsSpaceFunctionForBooking(1L, 5L)).thenReturn(true);

        service.applyStageStatus(1L, 3L, "TENTATIVE", "Proposal");

        assertThat(open.getStatusCode()).isEqualTo("TENTATIVE");
        assertThat(closed.getStatus()).isEqualTo(Booking.Status.LOST);

        open.setStatus(Booking.Status.DEFINITE);
        open.setStatusCode("DEFINITE");
        assertThatThrownBy(() -> service.applyStageStatus(1L, 3L, "INQUIRY", "Back to inquiry"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Pharma kongres")
                .hasMessageContaining("DEFINITE to INQUIRY");
    }

    @Test
    void bookingCannotBeLimitedToAHotelWhileItHasFunctionsElsewhere() {
        TenantHierarchy chain = org.mockito.Mockito.mock(TenantHierarchy.class);
        when(chain.isPropertyOf(2L, 1L)).thenReturn(true);
        when(chain.requirePropertyOf(2L, 1L)).thenReturn(2L);
        service = new BookingService(bookingRepo, historyRepo, functionRepo, outcomeRepo, accountRepo, contactRepo,
                opportunityRepo, reservationSync, itemPricing, accessContext, teamDirectory, transactionManager,
                quoteRepo, chain, lookupService, statusService, true);
        Booking existing = booking(Booking.Status.INQUIRY);
        existing.setPropertyTenantId(null);
        when(functionRepo.findForBooking(1L, 5L)).thenReturn(java.util.List.of(
                com.stackwizard.booking_api.model.BookingFunction.builder().id(9L).tenantId(3L).build()));
        Booking changes = Booking.builder().name(existing.getName()).propertyTenantId(2L)
                .dateFrom(existing.getDateFrom()).dateTo(existing.getDateTo()).build();

        assertThatThrownBy(() -> service.update(5L, changes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("other hotels");
        assertThat(existing.getPropertyTenantId()).isNull();
    }
}
