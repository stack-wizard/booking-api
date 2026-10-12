package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.ResourceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventReadModelServiceTest {

    @Mock EventService eventService;
    @Mock EventRepository eventRepo;
    @Mock EventFunctionRepository functionRepo;
    @Mock EventFunctionItemRepository itemRepo;
    @Mock EventReservationSync reservationSync;
    @Mock ResourceRepository resourceRepo;
    @Mock AllocationRepository allocationRepo;

    @Test
    void financialsWhenNoFunctionHasARoom() {
        Event event = Event.builder().id(6L).tenantId(1L).currency("EUR")
                .status(Event.Status.DEFINITE).guaranteedPax(120).build();
        EventFunction function = EventFunction.builder()
                .id(5L).tenantId(1L).eventId(6L).name("Day Delegate Rate")
                .functionType(EventFunction.FunctionType.PLENARY).build();
        EventFunctionItem item = EventFunctionItem.builder()
                .eventFunctionId(5L).grossAmount(new BigDecimal("7800.00")).build();
        when(eventService.requireEvent(6L)).thenReturn(event);
        when(functionRepo.findForEvent(1L, 6L)).thenReturn(List.of(function));
        when(reservationSync.activeLines(any())).thenReturn(List.of());
        when(itemRepo.findForFunctions(eq(1L), eq(List.of(5L)))).thenReturn(List.of(item));

        EventReadModelService service = new EventReadModelService(
                eventService, eventRepo, functionRepo, itemRepo, reservationSync,
                resourceRepo, allocationRepo, null);

        EventDtos.Financials financials = service.financials(6L);

        assertThat(financials.getItemsTotal()).isEqualByComparingTo("7800.00");
        assertThat(financials.getTotal()).isEqualByComparingTo("7800.00");
        assertThat(financials.getFunctions()).singleElement().satisfies(row -> {
            assertThat(row.getResourceId()).isNull();
            assertThat(row.getResourceName()).isNull();
        });
    }
}
