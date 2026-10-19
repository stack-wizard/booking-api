package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.BookingStatusFlow;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Statuses and allowed moves of one chain. Every move must respect {@link #PHASE_TRANSITIONS}. A status with
 * configured outgoing moves allows only those; a status without any falls back to the phase rules unless it is linked
 * to an OPERA status (then, as in OPERA, it is final).
 */
public final class BookingStatusRules {
    static final Map<Booking.Status, Set<Booking.Status>> PHASE_TRANSITIONS = Map.of(
            Booking.Status.INQUIRY, EnumSet.of(Booking.Status.TENTATIVE, Booking.Status.TURNED_DOWN, Booking.Status.LOST),
            Booking.Status.TENTATIVE, EnumSet.of(Booking.Status.DEFINITE, Booking.Status.ACTUAL, Booking.Status.TURNED_DOWN,
                    Booking.Status.LOST, Booking.Status.CANCELLED, Booking.Status.INQUIRY),
            Booking.Status.DEFINITE, EnumSet.of(Booking.Status.ACTUAL, Booking.Status.CANCELLED),
            Booking.Status.ACTUAL, EnumSet.noneOf(Booking.Status.class),
            Booking.Status.TURNED_DOWN, EnumSet.noneOf(Booking.Status.class),
            Booking.Status.LOST, EnumSet.noneOf(Booking.Status.class),
            Booking.Status.CANCELLED, EnumSet.noneOf(Booking.Status.class)
    );

    private static final String[][] DEFAULTS = {
            {"INQUIRY", "Inquiry"},
            {"TENTATIVE", "Tentative"},
            {"DEFINITE", "Definite"},
            {"ACTUAL", "Actual"},
            {"TURNED_DOWN", "Refused"},
            {"LOST", "Lost"},
            {"CANCELLED", "Cancelled"},
    };

    private final List<BookingStatus> statuses;
    private final Map<Long, Set<Long>> next = new HashMap<>();

    public BookingStatusRules(List<BookingStatus> statuses, List<BookingStatusFlow> flow) {
        this.statuses = List.copyOf(statuses);
        for (BookingStatusFlow edge : flow) {
            next.computeIfAbsent(edge.getFromStatusId(), k -> new HashSet<>()).add(edge.getToStatusId());
        }
    }

    /** One status per phase (code = phase name), INQUIRY first; display order 10, 20, ... */
    static List<BookingStatus> defaultStatuses() {
        List<BookingStatus> out = new ArrayList<>();
        for (int i = 0; i < DEFAULTS.length; i++) {
            Booking.Status phase = Booking.Status.valueOf(DEFAULTS[i][0]);
            out.add(BookingStatus.builder()
                    .code(DEFAULTS[i][0])
                    .label(DEFAULTS[i][1])
                    .phase(phase)
                    .start(phase == Booking.Status.INQUIRY)
                    .displayOrder((i + 1) * 10)
                    .active(true)
                    .build());
        }
        return out;
    }

    /** Rules of a chain that never configured anything; used by tests and as the seed. */
    static BookingStatusRules defaults() {
        List<BookingStatus> statuses = defaultStatuses();
        for (int i = 0; i < statuses.size(); i++) {
            statuses.get(i).setId((long) i + 1);
        }
        return new BookingStatusRules(statuses, defaultFlow(statuses));
    }

    /** Moves between the default statuses that follow the phase rules. */
    static List<BookingStatusFlow> defaultFlow(List<BookingStatus> statuses) {
        List<BookingStatusFlow> flow = new ArrayList<>();
        for (BookingStatus from : statuses) {
            for (BookingStatus to : statuses) {
                if (PHASE_TRANSITIONS.get(from.getPhase()).contains(to.getPhase())) {
                    flow.add(BookingStatusFlow.builder().fromStatusId(from.getId()).toStatusId(to.getId()).build());
                }
            }
        }
        return flow;
    }

    public static String codeOf(Booking booking) {
        return booking.getStatusCode() != null ? booking.getStatusCode() : booking.getStatus().name();
    }

    static boolean phaseAllows(Booking.Status from, Booking.Status to) {
        return from == to || PHASE_TRANSITIONS.getOrDefault(from, Set.of()).contains(to);
    }

    public List<BookingStatus> statuses() {
        return statuses;
    }

    public Set<Long> nextIds(Long statusId) {
        return next.getOrDefault(statusId, Set.of());
    }

    public Optional<BookingStatus> find(String code) {
        if (code == null) {
            return Optional.empty();
        }
        String wanted = code.trim();
        return statuses.stream().filter(s -> s.getCode().equalsIgnoreCase(wanted)).findFirst();
    }

    /** Status the booking is in; a code unknown to the chain becomes a status without configured moves. */
    public BookingStatus current(Booking booking) {
        String code = codeOf(booking);
        return find(code)
                .filter(s -> s.getPhase() == booking.getStatus())
                .orElseGet(() -> synthetic(code, booking.getStatus()));
    }

    public boolean allows(BookingStatus from, BookingStatus to) {
        if (!Boolean.TRUE.equals(to.getActive()) || from.getCode().equalsIgnoreCase(to.getCode())
                || !phaseAllows(from.getPhase(), to.getPhase())) {
            return false;
        }
        Set<Long> configured = from.getId() == null ? null : next.get(from.getId());
        if (configured == null) {
            return from.getOperaCode() == null;
        }
        return configured.contains(to.getId());
    }

    public List<BookingStatus> next(Booking booking) {
        BookingStatus from = current(booking);
        return statuses.stream().filter(to -> allows(from, to)).toList();
    }

    /**
     * Status to move to: the requested code, or else the first allowed status of the requested phase.
     *
     * @throws IllegalArgumentException for an unknown code
     * @throws IllegalStateException when the move is not allowed
     */
    public BookingStatus target(Booking booking, String code, Booking.Status phase) {
        BookingStatus from = current(booking);
        if (code != null && !code.isBlank()) {
            BookingStatus to = find(code).orElseThrow(() -> new IllegalArgumentException("Unknown booking status: " + code.trim()));
            if (from.getCode().equalsIgnoreCase(to.getCode())) {
                throw new IllegalStateException("Booking is already " + to.getCode());
            }
            if (!allows(from, to)) {
                throw new IllegalStateException("Cannot move booking from " + from.getCode() + " to " + to.getCode());
            }
            return to;
        }
        if (phase == null) {
            throw new IllegalArgumentException("status is required");
        }
        if (phase == booking.getStatus()) {
            throw new IllegalStateException("Booking is already " + phase);
        }
        return next(booking).stream()
                .filter(s -> s.getPhase() == phase)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Cannot move booking from " + from.getCode() + " to " + phase));
    }

    /** Status of a new booking. */
    public BookingStatus start() {
        return statuses.stream()
                .filter(s -> Boolean.TRUE.equals(s.getActive()) && Boolean.TRUE.equals(s.getStart()))
                .findFirst()
                .orElseGet(() -> firstOfPhase(Booking.Status.INQUIRY));
    }

    /** First active status of the phase, for moves made by the system (option expiry). */
    public BookingStatus firstOfPhase(Booking.Status phase) {
        return statuses.stream()
                .filter(s -> Boolean.TRUE.equals(s.getActive()) && s.getPhase() == phase)
                .findFirst()
                .orElseGet(() -> synthetic(phase.name(), phase));
    }

    private static BookingStatus synthetic(String code, Booking.Status phase) {
        return BookingStatus.builder().code(code).label(code).phase(phase).start(false).active(true).build();
    }
}
