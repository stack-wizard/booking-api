package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.BookingStatusDtos;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.BookingStatusFlow;
import com.stackwizard.booking_api.model.OperaCode;
import com.stackwizard.booking_api.model.OperaHotel;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.BookingStatusFlowRepository;
import com.stackwizard.booking_api.repository.BookingStatusRepository;
import com.stackwizard.booking_api.repository.OperaCodeRepository;
import com.stackwizard.booking_api.repository.OperaHotelRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.service.opera.OperaStatusGraph;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Chain booking statuses. Codes are saved on bookings and never change; a status is deactivated, not deleted.
 * Its phase can change only while no booking uses it.
 */
@Service
public class BookingStatusService {
    private static final Pattern CODE = Pattern.compile("[A-Z0-9_]{1,40}");
    private static final Pattern COLOR = Pattern.compile("#[0-9a-fA-F]{6}");
    /** Phases a pipeline stage may set; closing needs a reason and goes through the close dialog. */
    private static final Set<Booking.Status> STAGE_PHASES = EnumSet.of(
            Booking.Status.INQUIRY, Booking.Status.TENTATIVE, Booking.Status.DEFINITE, Booking.Status.ACTUAL);
    private static final Map<String, Booking.Status> OPERA_STATUS_TYPES = Map.of(
            "Inquiry", Booking.Status.INQUIRY,
            "Waitlist", Booking.Status.INQUIRY,
            "Referral", Booking.Status.INQUIRY,
            "NonDeductInv", Booking.Status.TENTATIVE,
            "DeductInv", Booking.Status.DEFINITE,
            "Actual", Booking.Status.ACTUAL,
            "Lost", Booking.Status.LOST,
            "Refused", Booking.Status.TURNED_DOWN,
            "Cancel", Booking.Status.CANCELLED);
    private static final Map<String, String> OPERA_COLORS = Map.of(
            "Red", "#d32f2f", "Blue", "#1976d2", "Cyan", "#0097a7", "Magenta", "#c2185b", "Green", "#2e7d32",
            "Brown", "#6d4c41", "Black", "#212121", "Yellow", "#f9a825", "Gray", "#757575");

    private final BookingStatusRepository repo;
    private final BookingStatusFlowRepository flowRepo;
    private final BookingRepository bookingRepo;
    private final OperaHotelRepository operaHotelRepo;
    private final OperaCodeRepository operaCodeRepo;
    private final TenantHierarchy hierarchy;
    private final JdbcTemplate jdbc;
    private final CrmAccessContext accessContext;

    public BookingStatusService(BookingStatusRepository repo,
                              BookingStatusFlowRepository flowRepo,
                              BookingRepository bookingRepo,
                              OperaHotelRepository operaHotelRepo,
                              OperaCodeRepository operaCodeRepo,
                              TenantHierarchy hierarchy,
                              JdbcTemplate jdbc,
                              CrmAccessContext accessContext) {
        this.repo = repo;
        this.flowRepo = flowRepo;
        this.bookingRepo = bookingRepo;
        this.operaHotelRepo = operaHotelRepo;
        this.operaCodeRepo = operaCodeRepo;
        this.hierarchy = hierarchy;
        this.jdbc = jdbc;
        this.accessContext = accessContext;
    }

    @Transactional
    public BookingStatusRules rules(Long orgTenantId) {
        ensureDefaults(orgTenantId);
        return new BookingStatusRules(repo.findByTenantIdOrderByDisplayOrderAscIdAsc(orgTenantId), flowRepo.findByTenantId(orgTenantId));
    }

    @Transactional
    public List<BookingStatusDtos.Status> list() {
        BookingStatusRules rules = rules(TenantResolver.requireOrgTenantId());
        return rules.statuses().stream().map(s -> toDto(s, rules)).toList();
    }

    @Transactional
    public BookingStatusDtos.Status create(BookingStatusDtos.Status request) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Long tenantId = TenantResolver.requireOrgTenantId();
        BookingStatusRules rules = rules(tenantId);
        String code = normalizeNewCode(request.getCode());
        if (rules.find(code).isPresent()) {
            throw new IllegalArgumentException("Code " + code + " already exists");
        }
        if (request.getPhase() == null) {
            throw new IllegalArgumentException("phase is required");
        }
        requireOperaLink(tenantId, request.getPhase(), normalizeOperaCode(request.getOperaCode()));
        BookingStatus status = BookingStatus.builder()
                .tenantId(tenantId)
                .code(code)
                .label(requireLabel(request.getLabel()))
                .phase(request.getPhase())
                .color(normalizeColor(request.getColor()))
                .start(requireStart(request.getStart(), request.getPhase()))
                .displayOrder(request.getDisplayOrder() != null ? request.getDisplayOrder()
                        : rules.statuses().stream().mapToInt(BookingStatus::getDisplayOrder).max().orElse(0) + 10)
                .active(request.getActive() == null || request.getActive())
                .operaCode(normalizeOperaCode(request.getOperaCode()))
                .build();
        BookingStatus saved = repo.save(status);
        if (request.getNext() != null) {
            replaceNext(saved, request.getNext(), rules(tenantId));
        }
        return toDto(saved, rules(tenantId));
    }

    @Transactional
    public BookingStatusDtos.Status update(Long id, BookingStatusDtos.Status changes) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        if (changes == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Long tenantId = TenantResolver.requireOrgTenantId();
        BookingStatus existing = repo.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Booking status not found: " + id));
        if (changes.getCode() != null && !changes.getCode().trim().equalsIgnoreCase(existing.getCode())) {
            throw new IllegalArgumentException("Code cannot be changed; add a new status and deactivate this one");
        }
        if (changes.getPhase() != null && changes.getPhase() != existing.getPhase()) {
            if (bookingRepo.existsByTenantIdAndStatusCodeIgnoreCase(tenantId, existing.getCode())) {
                throw new IllegalStateException("Status " + existing.getCode() + " is used by bookings; its phase cannot change");
            }
            existing.setPhase(changes.getPhase());
            dropInvalidMoves(tenantId, existing);
        }
        if (changes.getLabel() != null) {
            existing.setLabel(requireLabel(changes.getLabel()));
        }
        if (changes.getColor() != null) {
            existing.setColor(normalizeColor(changes.getColor()));
        }
        if (changes.getDisplayOrder() != null) {
            existing.setDisplayOrder(changes.getDisplayOrder());
        }
        if (changes.getActive() != null) {
            existing.setActive(changes.getActive());
        }
        existing.setStart(requireStart(changes.getStart() != null ? changes.getStart() : existing.getStart(), existing.getPhase()));
        if (changes.getOperaCode() != null) {
            existing.setOperaCode(normalizeOperaCode(changes.getOperaCode()));
        }
        if (Boolean.TRUE.equals(existing.getActive())) {
            requireOperaLink(tenantId, existing.getPhase(), existing.getOperaCode());
        }
        BookingStatus saved = repo.save(existing);
        if (changes.getNext() != null) {
            replaceNext(saved, changes.getNext(), rules(tenantId));
        }
        return toDto(saved, rules(tenantId));
    }

    /**
     * Canonical status code a pipeline stage sets on the open bookings of its deals; null clears it. The current value
     * stays valid when its status was deactivated.
     */
    @Transactional
    public String requireStageStatus(Long orgTenantId, String value, String previous) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        if (previous != null && value.trim().equalsIgnoreCase(previous)) {
            return previous;
        }
        BookingStatus status = rules(orgTenantId).find(value)
                .filter(s -> Boolean.TRUE.equals(s.getActive()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown booking status: " + value.trim()));
        if (!STAGE_PHASES.contains(status.getPhase())) {
            throw new IllegalArgumentException("A stage can set only inquiry, tentative, definite or actual statuses");
        }
        return status.getCode();
    }

    /** OPERA hotels of the chain's hotels, to pick the source of an import. */
    public List<OperaHotel> operaHotels() {
        Long org = TenantResolver.requireOrgTenantId();
        List<OperaHotel> out = new ArrayList<>();
        for (Long property : hierarchy.familyIdsOf(org)) {
            out.addAll(operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(property));
        }
        return out;
    }

    /**
     * Creates a status for every OPERA block status imported for the hotel (or links an existing one with the same
     * code) and adds the OPERA status flow where the phase rules allow it.
     */
    @Transactional
    public BookingStatusDtos.OperaImportResult importFromOpera(Long operaHotelId) {
        accessContext.require(CrmPermission.PIPELINE_CONFIG);
        Long org = TenantResolver.requireOrgTenantId();
        OperaHotel hotel = operaHotelRepo.findById(operaHotelId == null ? -1L : operaHotelId)
                .filter(h -> hierarchy.familyIdsOf(org).contains(h.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("OPERA hotel not found: " + operaHotelId));
        List<OperaCode> codes = operaCodeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                        hotel.getTenantId(), hotel.getId(), OperaCode.Kind.BLOCK_STATUS).stream()
                .filter(c -> Boolean.TRUE.equals(c.getActive()))
                .toList();
        if (codes.isEmpty()) {
            throw new IllegalStateException("Import OPERA codes for hotel " + hotel.getHotelCode() + " first");
        }
        BookingStatusDtos.OperaImportResult result = new BookingStatusDtos.OperaImportResult();
        BookingStatusRules rules = rules(org);
        for (OperaCode code : codes) {
            Optional<BookingStatus> local = rules.statuses().stream()
                    .filter(s -> code.getCode().equalsIgnoreCase(s.getOperaCode()) || code.getCode().equalsIgnoreCase(s.getCode()))
                    .findFirst();
            if (local.isPresent()) {
                if (local.get().getOperaCode() == null) {
                    local.get().setOperaCode(code.getCode());
                    repo.save(local.get());
                    result.setLinked(result.getLinked() + 1);
                }
                continue;
            }
            Booking.Status phase = suggestPhase(code.getAttrs());
            repo.save(BookingStatus.builder()
                    .tenantId(org)
                    .code(toLocalCode(code.getCode()))
                    .label(StringUtils.hasText(code.getDescription()) ? code.getDescription().trim() : code.getCode())
                    .phase(phase)
                    .color(OPERA_COLORS.get(text(code.getAttrs(), "color")))
                    .start(phase == Booking.Status.INQUIRY && code.getAttrs() != null && code.getAttrs().path("starting").asBoolean(false))
                    .displayOrder(100 + code.getAttrs().path("orderBy").asInt(0) * 10)
                    .active(true)
                    .operaCode(code.getCode())
                    .build());
            result.setCreated(result.getCreated() + 1);
        }
        rules = rules(org);
        for (OperaCode code : codes) {
            BookingStatus from = byOperaCode(rules, code.getCode());
            for (JsonNode nextCode : code.getAttrs().path("next")) {
                BookingStatus to = byOperaCode(rules, nextCode.asText());
                if (from == null || to == null || from.getId().equals(to.getId())) {
                    result.setSkippedTransitions(result.getSkippedTransitions() + 1);
                } else if (!BookingStatusRules.phaseAllows(from.getPhase(), to.getPhase())) {
                    result.setSkippedTransitions(result.getSkippedTransitions() + 1);
                } else if (!rules.nextIds(from.getId()).contains(to.getId())) {
                    insertMove(org, from.getId(), to.getId());
                    result.setTransitions(result.getTransitions() + 1);
                }
            }
        }
        alignWithOpera(org, new OperaStatusGraph(codes), result);
        return result;
    }

    /**
     * Refresh: moves OPERA does not allow are removed, statuses without a live OPERA link are deactivated and the start
     * status is an OPERA starting inquiry status.
     */
    private void alignWithOpera(Long org, OperaStatusGraph graph, BookingStatusDtos.OperaImportResult result) {
        BookingStatusRules rules = rules(org);
        Map<Long, BookingStatus> byId = new java.util.HashMap<>();
        rules.statuses().forEach(s -> byId.put(s.getId(), s));
        for (BookingStatus status : rules.statuses()) {
            boolean linked = status.getOperaCode() != null && graph.find(status.getOperaCode()).isPresent();
            if (Boolean.TRUE.equals(status.getActive()) && !linked) {
                status.setActive(false);
                status.setStart(false);
                repo.save(status);
                result.setDeactivated(result.getDeactivated() + 1);
            }
        }
        List<BookingStatusFlow> invalid = flowRepo.findByTenantId(org).stream()
                .filter(f -> {
                    BookingStatus from = byId.get(f.getFromStatusId());
                    BookingStatus to = byId.get(f.getToStatusId());
                    return from == null || to == null || !Boolean.TRUE.equals(from.getActive()) || !Boolean.TRUE.equals(to.getActive())
                            || !graph.allows(from.getOperaCode(), to.getOperaCode());
                })
                .toList();
        flowRepo.deleteAll(invalid);
        result.setRemovedTransitions(invalid.size());
        boolean hasStart = rules.statuses().stream()
                .anyMatch(s -> Boolean.TRUE.equals(s.getActive()) && Boolean.TRUE.equals(s.getStart()));
        if (!hasStart) {
            rules.statuses().stream()
                    .filter(s -> Boolean.TRUE.equals(s.getActive()) && s.getPhase() == Booking.Status.INQUIRY)
                    .filter(s -> graph.find(s.getOperaCode()).map(c -> c.getAttrs().path("starting").asBoolean(false)).orElse(false))
                    .findFirst()
                    .ifPresent(s -> {
                        s.setStart(true);
                        repo.save(s);
                    });
        }
    }

    public static Booking.Status suggestPhase(JsonNode attrs) {
        String type = text(attrs, "cateringStatusType");
        if (type == null) {
            type = text(attrs, "roomStatusType");
        }
        return type == null ? Booking.Status.INQUIRY : OPERA_STATUS_TYPES.getOrDefault(type, Booking.Status.INQUIRY);
    }

    private void ensureDefaults(Long tenantId) {
        if (repo.existsByTenantId(tenantId)) {
            return;
        }
        for (BookingStatus status : BookingStatusRules.defaultStatuses()) {
            jdbc.update("""
                    insert into booking_status (tenant_id, code, label, phase, is_start, display_order, active)
                    values (?, ?, ?, ?, ?, ?, true)
                    on conflict (tenant_id, upper(code)) do nothing
                    """, tenantId, status.getCode(), status.getLabel(), status.getPhase().name(), status.getStart(),
                    status.getDisplayOrder());
        }
        List<BookingStatus> saved = repo.findByTenantIdOrderByDisplayOrderAscIdAsc(tenantId);
        for (BookingStatusFlow move : BookingStatusRules.defaultFlow(saved)) {
            insertMove(tenantId, move.getFromStatusId(), move.getToStatusId());
        }
    }

    private void insertMove(Long tenantId, Long fromId, Long toId) {
        jdbc.update("""
                insert into booking_status_flow (tenant_id, from_status_id, to_status_id)
                values (?, ?, ?)
                on conflict (from_status_id, to_status_id) do nothing
                """, tenantId, fromId, toId);
    }

    private void replaceNext(BookingStatus from, List<String> codes, BookingStatusRules rules) {
        List<Long> targets = new ArrayList<>();
        OperaStatusGraph graph = operaGraph(from.getTenantId());
        for (String code : codes) {
            BookingStatus to = rules.find(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown booking status: " + code));
            if (to.getId().equals(from.getId()) || !BookingStatusRules.phaseAllows(from.getPhase(), to.getPhase())) {
                throw new IllegalArgumentException(from.getCode() + " (" + from.getPhase() + ") cannot move to "
                        + to.getCode() + " (" + to.getPhase() + ")");
            }
            if (!graph.isEmpty() && !graph.allows(from.getOperaCode(), to.getOperaCode())) {
                throw new IllegalArgumentException(from.getCode() + " cannot move to " + to.getCode() + ": OPERA does not allow "
                        + from.getOperaCode() + " → " + to.getOperaCode());
            }
            if (!targets.contains(to.getId())) {
                targets.add(to.getId());
            }
        }
        flowRepo.deleteAll(flowRepo.findByTenantIdAndFromStatusId(from.getTenantId(), from.getId()));
        flowRepo.flush();
        for (Long to : targets) {
            insertMove(from.getTenantId(), from.getId(), to);
        }
    }

    /** After a phase change, moves to or from the status that the phase rules no longer allow are removed. */
    private void dropInvalidMoves(Long tenantId, BookingStatus changed) {
        Map<Long, BookingStatus> byId = new java.util.HashMap<>();
        repo.findByTenantIdOrderByDisplayOrderAscIdAsc(tenantId).forEach(s -> byId.put(s.getId(), s));
        byId.put(changed.getId(), changed);
        List<BookingStatusFlow> invalid = flowRepo.findByTenantId(tenantId).stream()
                .filter(f -> Objects.equals(f.getFromStatusId(), changed.getId()) || Objects.equals(f.getToStatusId(), changed.getId()))
                .filter(f -> {
                    BookingStatus from = byId.get(f.getFromStatusId());
                    BookingStatus to = byId.get(f.getToStatusId());
                    return from == null || to == null || !BookingStatusRules.phaseAllows(from.getPhase(), to.getPhase());
                })
                .toList();
        flowRepo.deleteAll(invalid);
    }

    /** Union of the active OPERA block statuses of the chain's hotels; empty when no hotel imported them. */
    private OperaStatusGraph operaGraph(Long orgTenantId) {
        List<OperaCode> codes = new ArrayList<>();
        for (Long property : hierarchy.familyIdsOf(orgTenantId)) {
            for (OperaHotel hotel : operaHotelRepo.findByTenantIdOrderByHotelCodeAscIdAsc(property)) {
                if (!Boolean.FALSE.equals(hotel.getActive())) {
                    codes.addAll(operaCodeRepo.findByTenantIdAndOperaHotelIdAndKindOrderByCodeAsc(
                            hotel.getTenantId(), hotel.getId(), OperaCode.Kind.BLOCK_STATUS));
                }
            }
        }
        return new OperaStatusGraph(codes);
    }

    /** Once OPERA statuses are imported every active status stands for an OPERA status of the same phase. */
    private void requireOperaLink(Long orgTenantId, Booking.Status phase, String operaCode) {
        OperaStatusGraph graph = operaGraph(orgTenantId);
        if (graph.isEmpty()) {
            return;
        }
        if (operaCode == null) {
            throw new IllegalArgumentException("operaCode is required: the chain follows OPERA block statuses");
        }
        Booking.Status operaPhase = graph.phase(operaCode);
        if (operaPhase == null) {
            throw new IllegalArgumentException("Unknown OPERA block status " + operaCode);
        }
        if (operaPhase != phase) {
            throw new IllegalArgumentException("OPERA status " + operaCode + " is " + operaPhase + ", not " + phase);
        }
    }

    private static BookingStatus byOperaCode(BookingStatusRules rules, String operaCode) {
        return rules.statuses().stream()
                .filter(s -> operaCode != null && operaCode.equalsIgnoreCase(s.getOperaCode()))
                .findFirst()
                .orElse(null);
    }

    private static BookingStatusDtos.Status toDto(BookingStatus status, BookingStatusRules rules) {
        List<String> next = rules.statuses().stream()
                .filter(s -> rules.nextIds(status.getId()).contains(s.getId()))
                .map(BookingStatus::getCode)
                .toList();
        return BookingStatusDtos.Status.builder()
                .id(status.getId())
                .code(status.getCode())
                .label(status.getLabel())
                .phase(status.getPhase())
                .color(status.getColor())
                .start(status.getStart())
                .displayOrder(status.getDisplayOrder())
                .active(status.getActive())
                .operaCode(status.getOperaCode())
                .next(next)
                .build();
    }

    private static Boolean requireStart(Boolean start, Booking.Status phase) {
        if (Boolean.TRUE.equals(start) && phase != Booking.Status.INQUIRY) {
            throw new IllegalArgumentException("Only an inquiry status can be the start status");
        }
        return Boolean.TRUE.equals(start);
    }

    private static String toLocalCode(String operaCode) {
        String code = operaCode.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]+", "_");
        return code.length() > 40 ? code.substring(0, 40) : code;
    }

    private static String normalizeNewCode(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("code is required");
        }
        String code = value.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s-]+", "_");
        if (!CODE.matcher(code).matches()) {
            throw new IllegalArgumentException("code may contain only letters, digits and _ (max 40)");
        }
        return code;
    }

    private static String normalizeColor(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        String color = value.trim();
        if (!COLOR.matcher(color).matches()) {
            throw new IllegalArgumentException("color must be a hex value like #1976d2");
        }
        return color.toLowerCase(Locale.ROOT);
    }

    private static String normalizeOperaCode(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private static String requireLabel(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("label is required");
        }
        return value.trim();
    }

    private static String text(JsonNode attrs, String key) {
        if (attrs == null || !attrs.hasNonNull(key)) {
            return null;
        }
        String value = attrs.get(key).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
