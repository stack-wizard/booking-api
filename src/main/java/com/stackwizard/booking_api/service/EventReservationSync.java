package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the generated reservation line of each event function in step with the event status:
 * TENTATIVE holds until the decision date, DEFINITE confirms, everything else releases.
 */
@Component
public class EventReservationSync {
    private final ReservationService reservationService;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final EventFunctionRepository functionRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;

    public EventReservationSync(ReservationService reservationService,
                                ReservationRepository reservationRepo,
                                AllocationRepository allocationRepo,
                                EventFunctionRepository functionRepo,
                                CrmAccountRepository accountRepo,
                                CrmContactRepository contactRepo) {
        this.reservationService = reservationService;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.functionRepo = functionRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
    }

    @Transactional
    public void syncEvent(Event event) {
        for (EventFunction function : functionRepo.findForEvent(
                event.getTenantId(), event.getId())) {
            syncFunction(event, function);
        }
    }

    @Transactional
    public void syncFunction(Event event, EventFunction function) {
        List<Reservation> active = reservationRepo.findActiveByEventFunctionId(function.getTenantId(), function.getId());
        if (!event.getStatus().holdsSpace() || function.getResourceId() == null) {
            reservationService.releaseEventLines(active);
            return;
        }
        OffsetDateTime expiresAt = event.getStatus() == Event.Status.TENTATIVE ? holdExpiry(event) : null;
        Reservation current = active.stream().filter(r -> matches(r, function)).findFirst().orElse(null);
        List<Reservation> stale = active.stream().filter(r -> r != current).toList();
        reservationService.releaseEventLines(stale);

        if (current == null) {
            Reservation line = reservationService.holdEventFunctionLine(command(event, function, expiresAt));
            if (event.getStatus() == Event.Status.DEFINITE) {
                reservationService.confirmEventLines(List.of(line));
            }
            return;
        }
        if (event.getStatus() == Event.Status.DEFINITE) {
            reservationService.confirmEventLines(List.of(current));
        } else {
            reservationService.extendEventLineHolds(List.of(current), expiresAt);
        }
    }

    /**
     * Releases and physically removes the function's lines so the function row can be deleted.
     */
    @Transactional
    public void detachFunction(Event event, EventFunction function) {
        List<Reservation> lines = reservationRepo.findByTenantIdAndEventFunctionIdIn(
                function.getTenantId(), List.of(function.getId()));
        if (lines.isEmpty()) {
            return;
        }
        reservationService.releaseEventLines(lines);
        allocationRepo.deleteByReservationIdIn(lines.stream().map(Reservation::getId).toList());
        reservationRepo.deleteAll(lines);
    }

    /** Active lines of the functions; every line belongs to the hotel that hosts its function. */
    public List<Reservation> activeLines(List<EventFunction> functions) {
        List<Reservation> lines = new java.util.ArrayList<>();
        functions.stream()
                .collect(java.util.stream.Collectors.groupingBy(EventFunction::getTenantId,
                        java.util.stream.Collectors.mapping(EventFunction::getId, java.util.stream.Collectors.toList())))
                .forEach((tenantId, ids) -> lines.addAll(reservationRepo.findByTenantIdAndEventFunctionIdIn(tenantId, ids)));
        return lines.stream()
                .filter(r -> !"CANCELLED".equalsIgnoreCase(r.getStatus()))
                .toList();
    }

    static OffsetDateTime holdExpiry(Event event) {
        if (event.getDecisionDate() == null) {
            throw new IllegalStateException("decisionDate is required for a tentative hold");
        }
        return event.getDecisionDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
    }

    private static boolean matches(Reservation line, EventFunction function) {
        return line.getRequestedResource() != null
                && Objects.equals(line.getRequestedResource().getId(), function.getResourceId())
                && Objects.equals(line.getStartsAt(), function.getOccupancyStartsAt())
                && Objects.equals(line.getEndsAt(), function.getOccupancyEndsAt());
    }

    private ReservationService.EventLineCommand command(Event event, EventFunction function, OffsetDateTime expiresAt) {
        CrmAccount account = accountRepo.findByIdAndTenantId(event.getAccountId(), event.getTenantId()).orElse(null);
        CrmContact contact = event.getPrimaryContactId() == null ? null
                : contactRepo.findByIdAndTenantId(event.getPrimaryContactId(), event.getTenantId()).orElse(null);
        return new ReservationService.EventLineCommand(
                function.getTenantId(),
                function.getId(),
                function.getResourceId(),
                function.getOccupancyStartsAt(),
                function.getOccupancyEndsAt(),
                event.getCurrency(),
                account != null ? account.getName() : event.getName(),
                contact != null && contact.getEmail() != null ? contact.getEmail() : account != null ? account.getEmail() : null,
                contact != null && contact.getPhone() != null ? contact.getPhone() : account != null ? account.getPhone() : null,
                expiresAt,
                function.getPackageProductId() != null);
    }
}
