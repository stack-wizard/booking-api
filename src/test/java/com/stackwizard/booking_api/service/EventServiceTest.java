package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.model.EventStatusHistory;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.EventStatusHistoryRepository;
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
class EventServiceTest {

    @Mock EventRepository eventRepo;
    @Mock EventStatusHistoryRepository historyRepo;
    @Mock EventFunctionRepository functionRepo;
    @Mock CrmOutcomeReasonRepository outcomeRepo;
    @Mock CrmAccountRepository accountRepo;
    @Mock CrmContactRepository contactRepo;
    @Mock CrmOpportunityRepository opportunityRepo;
    @Mock EventReservationSync reservationSync;
    @Mock EventItemPricing itemPricing;
    @Mock CrmAccessContext accessContext;
    @Mock CrmTeamDirectory teamDirectory;
    @Mock PlatformTransactionManager transactionManager;
    @Mock SalesQuoteRepository quoteRepo;

    EventService service;

    @BeforeEach
    void setUp() {
        TenantContext.setTenantId(1L);
        when(accessContext.scope()).thenReturn(CrmScope.ALL);
        when(accessContext.currentUserId()).thenReturn(7L);
        when(eventRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        service = new EventService(eventRepo, historyRepo, functionRepo, outcomeRepo, accountRepo, contactRepo,
                opportunityRepo, reservationSync, itemPricing, accessContext, teamDirectory, transactionManager, quoteRepo, true);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private Event event(Event.Status status) {
        Event event = Event.builder()
                .id(5L).tenantId(1L).accountId(2L).name("Pharma kongres")
                .status(status)
                .dateFrom(LocalDate.now().plusDays(30)).dateTo(LocalDate.now().plusDays(32))
                .currency("EUR")
                .build();
        when(eventRepo.findByIdAndTenantId(5L, 1L)).thenReturn(Optional.of(event));
        return event;
    }

    private EventDtos.StatusChangeRequest to(Event.Status status, Long reasonId) {
        EventDtos.StatusChangeRequest request = new EventDtos.StatusChangeRequest();
        request.setStatus(status);
        request.setOutcomeReasonId(reasonId);
        return request;
    }

    @Test
    void inquiryCannotJumpToDefinite() {
        event(Event.Status.INQUIRY);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.DEFINITE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("INQUIRY to DEFINITE");
        verify(reservationSync, never()).syncEvent(any());
    }

    @Test
    void definiteRequiresAcceptedQuote() {
        event(Event.Status.TENTATIVE).setDecisionDate(LocalDate.now().plusDays(3));
        when(functionRepo.existsByTenantIdAndEventIdAndResourceIdIsNotNull(1L, 5L)).thenReturn(true);
        when(quoteRepo.existsByTenantIdAndEventIdAndStatus(1L, 5L, SalesQuote.Status.ACCEPTED)).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.DEFINITE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accepted quote");

        when(quoteRepo.existsByTenantIdAndEventIdAndStatus(1L, 5L, SalesQuote.Status.ACCEPTED)).thenReturn(true);
        assertThat(service.changeStatus(5L, to(Event.Status.DEFINITE, null)).getStatus()).isEqualTo(Event.Status.DEFINITE);
    }

    @Test
    void tentativeRequiresDecisionDate() {
        event(Event.Status.INQUIRY);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.TENTATIVE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decisionDate");
    }

    @Test
    void tentativeRequiresAtLeastOneFunctionWithSpace() {
        Event event = event(Event.Status.INQUIRY);
        event.setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsByTenantIdAndEventIdAndResourceIdIsNotNull(1L, 5L)).thenReturn(false);

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.TENTATIVE, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("space");
        verify(reservationSync, never()).syncEvent(any());
    }

    @Test
    void tentativeHoldsSpaceAndWritesHistory() {
        Event event = event(Event.Status.INQUIRY);
        event.setDecisionDate(LocalDate.now().plusDays(7));
        when(functionRepo.existsByTenantIdAndEventIdAndResourceIdIsNotNull(1L, 5L)).thenReturn(true);

        Event saved = service.changeStatus(5L, to(Event.Status.TENTATIVE, null));

        assertThat(saved.getStatus()).isEqualTo(Event.Status.TENTATIVE);
        verify(reservationSync).syncEvent(event);
        ArgumentCaptor<EventStatusHistory> history = ArgumentCaptor.forClass(EventStatusHistory.class);
        verify(historyRepo).save(history.capture());
        assertThat(history.getValue().getFromStatus()).isEqualTo(Event.Status.INQUIRY);
        assertThat(history.getValue().getToStatus()).isEqualTo(Event.Status.TENTATIVE);
        assertThat(history.getValue().getChangedBy()).isEqualTo(7L);
    }

    @Test
    void lostRequiresReasonOfKindLost() {
        event(Event.Status.TENTATIVE);
        when(outcomeRepo.findByIdAndTenantId(9L, 1L)).thenReturn(Optional.of(
                CrmOutcomeReason.builder().id(9L).tenantId(1L).kind(CrmOutcomeReason.Kind.TURNED_DOWN).build()));

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.LOST, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outcomeReasonId");
        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.LOST, 9L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kind must be LOST");
    }

    @Test
    void actualNeedsActualPax() {
        Event event = event(Event.Status.DEFINITE);
        event.setDateFrom(LocalDate.now().minusDays(2));
        event.setDateTo(LocalDate.now().minusDays(1));

        assertThatThrownBy(() -> service.changeStatus(5L, to(Event.Status.ACTUAL, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("actualPax");

        event.setActualPax(118);
        Event saved = service.changeStatus(5L, to(Event.Status.ACTUAL, null));
        assertThat(saved.getStatus()).isEqualTo(Event.Status.ACTUAL);
        verify(itemPricing).recalculatePerPax(event);
    }

    @Test
    void closedEventIsReadOnly() {
        event(Event.Status.LOST);

        assertThatThrownBy(() -> service.update(5L, Event.builder().name("x").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LOST");
    }

    @Test
    void guaranteedPaxCannotDropAfterDueDate() {
        Event event = event(Event.Status.DEFINITE);
        event.setGuaranteedPax(120);
        event.setGuaranteeDueDate(LocalDate.now().minusDays(1));
        when(accountRepo.findByIdAndTenantId(2L, 1L)).thenReturn(Optional.of(new com.stackwizard.booking_api.model.CrmAccount()));

        Event changes = Event.builder().name("Pharma kongres").accountId(2L)
                .dateFrom(event.getDateFrom()).dateTo(event.getDateTo())
                .guaranteeDueDate(event.getGuaranteeDueDate())
                .guaranteedPax(100).build();

        assertThatThrownBy(() -> service.update(5L, changes))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot be reduced");
    }
}
