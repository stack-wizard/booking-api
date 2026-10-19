package com.stackwizard.booking_api.service.opera;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.service.BookingStatusService;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Active OPERA block statuses of a hotel and the configured OPERA moves between them ({@code attrs.next}). */
public final class OperaStatusGraph {
    private final Map<String, OperaCode> byCode = new LinkedHashMap<>();

    public OperaStatusGraph(List<OperaCode> blockStatuses) {
        for (OperaCode code : blockStatuses) {
            if (code.getKind() == OperaCode.Kind.BLOCK_STATUS && Boolean.TRUE.equals(code.getActive())) {
                byCode.putIfAbsent(key(code.getCode()), code);
            }
        }
    }

    public boolean isEmpty() {
        return byCode.isEmpty();
    }

    public Optional<OperaCode> find(String code) {
        return code == null ? Optional.empty() : Optional.ofNullable(byCode.get(key(code)));
    }

    public List<OperaCode> codes() {
        return List.copyOf(byCode.values());
    }

    public List<String> next(String code) {
        List<String> out = new ArrayList<>();
        find(code).ifPresent(c -> c.getAttrs().path("next").forEach(n -> out.add(n.asText())));
        return out;
    }

    /** OPERA allows the move: same status or a configured next status. */
    public boolean allows(String from, String to) {
        if (from == null || to == null) {
            return false;
        }
        return key(from).equals(key(to)) || next(from).stream().anyMatch(n -> key(n).equals(key(to)));
    }

    /** Shortest chain of OPERA moves from {@code from} to {@code to}, without {@code from}; empty when impossible. */
    public List<String> path(String from, String to) {
        if (from == null || to == null || key(from).equals(key(to))) {
            return List.of();
        }
        Map<String, String> parent = new HashMap<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(key(from));
        parent.put(key(from), null);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (String n : next(current)) {
                String k = key(n);
                if (parent.containsKey(k) || !byCode.containsKey(k)) {
                    continue;
                }
                parent.put(k, current);
                if (k.equals(key(to))) {
                    List<String> path = new ArrayList<>();
                    for (String step = k; step != null && !step.equals(key(from)); step = parent.get(step)) {
                        path.add(byCode.get(step).getCode());
                    }
                    Collections.reverse(path);
                    return path;
                }
                queue.add(k);
            }
        }
        return List.of();
    }

    /** Status a new OPERA block starts in on its way to {@code target}: the target itself when OPERA allows it. */
    public String startingFor(String target) {
        if (find(target).map(OperaStatusGraph::starting).orElse(false)) {
            return find(target).get().getCode();
        }
        String best = null;
        int bestLength = Integer.MAX_VALUE;
        for (OperaCode code : byCode.values()) {
            if (!starting(code)) {
                continue;
            }
            List<String> path = path(code.getCode(), target);
            if (!path.isEmpty() && path.size() < bestLength) {
                best = code.getCode();
                bestLength = path.size();
            }
        }
        if (best != null) {
            return best;
        }
        return byCode.values().stream().filter(OperaStatusGraph::starting).map(OperaCode::getCode).findFirst()
                .orElseThrow(() -> new IllegalStateException("OPERA has no starting block status; import OPERA codes"));
    }

    /** Booking phase an OPERA status stands for (catering status type first, then room status type). */
    public Booking.Status phase(String code) {
        return find(code).map(c -> BookingStatusService.suggestPhase(c.getAttrs())).orElse(null);
    }

    /** First OPERA status of a phase, used when a local status carries no OPERA code. */
    public Optional<String> firstOfPhase(Booking.Status phase) {
        return byCode.values().stream()
                .filter(c -> BookingStatusService.suggestPhase(c.getAttrs()) == phase)
                .sorted((a, b) -> Integer.compare(order(a), order(b)))
                .map(OperaCode::getCode)
                .findFirst();
    }

    /** Rooms of the block deduct house inventory (DeductInv or Actual). */
    public boolean deducts(String code) {
        String type = find(code).map(c -> text(c.getAttrs(), "roomStatusType")).orElse(null);
        return "DeductInv".equalsIgnoreCase(type) || "Actual".equalsIgnoreCase(type);
    }

    public boolean returnsToInventory(String code) {
        return find(code).map(c -> c.getAttrs().path("returnToInventory").asBoolean(false)).orElse(false);
    }

    public boolean allowsPickup(String code) {
        return find(code).map(c -> c.getAttrs().path("allowPickup").asBoolean(false)).orElse(false);
    }

    private static boolean starting(OperaCode code) {
        return code.getAttrs() != null && code.getAttrs().path("starting").asBoolean(false);
    }

    private static int order(OperaCode code) {
        return code.getAttrs() == null ? Integer.MAX_VALUE : code.getAttrs().path("orderBy").asInt(Integer.MAX_VALUE);
    }

    private static String text(JsonNode attrs, String key) {
        return attrs == null || !attrs.hasNonNull(key) ? null : attrs.get(key).asText();
    }

    private static String key(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }
}
