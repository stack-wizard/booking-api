package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.EventDtos;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventStatusHistory;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.EventRepository;
import com.stackwizard.booking_api.repository.EventStatusHistoryRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmOwnerScope;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class EventService {
    private static final Logger log = LoggerFactory.getLogger(EventService.class);
    static final String OPTION_EXPIRED_CODE = "OPTION_EXPIRED";
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    static final Map<Event.Status, Set<Event.Status>> TRANSITIONS = Map.of(
            Event.Status.INQUIRY, EnumSet.of(Event.Status.TENTATIVE, Event.Status.TURNED_DOWN, Event.Status.LOST),
            Event.Status.TENTATIVE, EnumSet.of(Event.Status.DEFINITE, Event.Status.LOST, Event.Status.INQUIRY),
            Event.Status.DEFINITE, EnumSet.of(Event.Status.ACTUAL, Event.Status.CANCELLED),
            Event.Status.ACTUAL, EnumSet.noneOf(Event.Status.class),
            Event.Status.TURNED_DOWN, EnumSet.noneOf(Event.Status.class),
            Event.Status.LOST, EnumSet.noneOf(Event.Status.class),
            Event.Status.CANCELLED, EnumSet.noneOf(Event.Status.class)
    );

    private final EventRepository eventRepo;
    private final EventStatusHistoryRepository historyRepo;
    private final EventFunctionRepository functionRepo;
    private final CrmOutcomeReasonRepository outcomeRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final EventReservationSync reservationSync;
    private final EventItemPricing itemPricing;
    private final CrmAccessContext accessContext;
    private final TransactionTemplate transactionTemplate;
    private final SalesQuoteRepository quoteRepo;
    private final boolean definiteRequiresAcceptedQuote;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventService(EventRepository eventRepo,
                        EventStatusHistoryRepository historyRepo,
                        EventFunctionRepository functionRepo,
                        CrmOutcomeReasonRepository outcomeRepo,
                        CrmAccountRepository accountRepo,
                        CrmContactRepository contactRepo,
                        CrmOpportunityRepository opportunityRepo,
                        EventReservationSync reservationSync,
                        EventItemPricing itemPricing,
                        CrmAccessContext accessContext,
                        PlatformTransactionManager transactionManager,
                        SalesQuoteRepository quoteRepo,
                        @Value("${crm.events.definite-requires-accepted-quote:true}") boolean definiteRequiresAcceptedQuote) {
        this.eventRepo = eventRepo;
        this.historyRepo = historyRepo;
        this.functionRepo = functionRepo;
        this.outcomeRepo = outcomeRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.reservationSync = reservationSync;
        this.itemPricing = itemPricing;
        this.accessContext = accessContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.quoteRepo = quoteRepo;
        this.definiteRequiresAcceptedQuote = definiteRequiresAcceptedQuote;
    }

    public List<Event> findAll(Event.Status status, Long accountId, LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return eventRepo.findScoped(TenantResolver.requireTenantId(), status, accountId,
                from != null ? from : MIN_DATE, to != null ? to : MAX_DATE,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds());
    }

    public Optional<Event> findById(Long id) {
        accessContext.require(CrmPermission.EVENT_READ);
        return eventRepo.findByIdAndTenantId(id, TenantResolver.requireTenantId())
                .filter(e -> CrmOwnerScope.from(accessContext).allows(e.getOwnerUserId()));
    }

    /** Tenant-scoped lookup without the caller's CRM scope, for scheduled jobs and the client portal. */
    public Optional<Event> findForSystem(Long tenantId, Long id) {
        return eventRepo.findByIdAndTenantId(id, tenantId);
    }

    public List<Event> forOpportunity(Long opportunityId) {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return eventRepo.findByTenantIdAndOpportunityIdOrderByDateFromAsc(TenantResolver.requireTenantId(), opportunityId)
                .stream().filter(e -> scope.allows(e.getOwnerUserId())).toList();
    }

    public Event requireEvent(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Event not found"));
    }

    /**
     * Event that may still be changed; closed outcomes are read-only, ACTUAL only accepts final numbers.
     */
    public Event requireEditable(Long id) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Event event = requireEvent(id);
        if (event.getStatus() == Event.Status.TURNED_DOWN
                || event.getStatus() == Event.Status.LOST
                || event.getStatus() == Event.Status.CANCELLED) {
            throw new IllegalStateException("Event is " + event.getStatus() + " and can no longer be changed");
        }
        return event;
    }

    @Transactional
    public Event create(Event event) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        event.setId(null);
        event.setTenantId(tenantId);
        event.setStatus(Event.Status.INQUIRY);
        event.setOutcomeReasonId(null);
        event.setOutcomeNote(null);
        event.setActualPax(null);
        if (!StringUtils.hasText(event.getCurrency())) {
            event.setCurrency("EUR");
        }
        if (event.getOwnerUserId() == null) {
            event.setOwnerUserId(accessContext.currentUserId());
        }
        if (event.getAttrs() == null) {
            event.setAttrs(objectMapper.createObjectNode());
        }
        if (event.getOpportunityId() != null) {
            CrmOpportunity opportunity = opportunityRepo.findByIdAndTenantId(event.getOpportunityId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Opportunity not found: " + event.getOpportunityId()));
            if (event.getAccountId() == null) {
                event.setAccountId(opportunity.getAccountId());
            }
        }
        validateHeader(tenantId, event);
        Event saved = eventRepo.save(event);
        writeHistory(saved, null, Event.Status.INQUIRY, null, "created", accessContext.currentUserId());
        return saved;
    }

    /**
     * New INQUIRY event prefilled from the opportunity intake (attrs title/eventType/startDate/endDate/pax).
     */
    @Transactional
    public Event createFromOpportunity(Long opportunityId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Long tenantId = TenantResolver.requireTenantId();
        CrmOpportunity opportunity = opportunityRepo.findByIdAndTenantId(opportunityId, tenantId)
                .filter(o -> CrmOwnerScope.from(accessContext).allows(o.getOwnerUserId()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Opportunity not found"));
        JsonNode attrs = opportunity.getAttrs();
        LocalDate from = parseDate(attrs, "startDate");
        if (from == null) {
            from = opportunity.getExpectedCloseDate() != null ? opportunity.getExpectedCloseDate() : LocalDate.now();
        }
        LocalDate to = parseDate(attrs, "endDate");
        if (to == null || to.isBefore(from)) {
            to = from;
        }
        Event event = Event.builder()
                .accountId(opportunity.getAccountId())
                .primaryContactId(opportunity.getPrimaryContactId())
                .opportunityId(opportunity.getId())
                .name(firstText(text(attrs, "title"), opportunity.getName()))
                .eventType(text(attrs, "eventType"))
                .dateFrom(from)
                .dateTo(to)
                .expectedPax(intValue(attrs, "pax"))
                .currency(opportunity.getCurrency())
                .ownerUserId(opportunity.getOwnerUserId())
                .notes(text(attrs, "notes"))
                .build();
        return create(event);
    }

    @Transactional
    public Event update(Long id, Event changes) {
        Event existing = requireEditable(id);
        Long tenantId = existing.getTenantId();
        boolean actual = existing.getStatus() == Event.Status.ACTUAL;
        if (!actual) {
            existing.setName(changes.getName());
            existing.setAccountId(changes.getAccountId() != null ? changes.getAccountId() : existing.getAccountId());
            existing.setPrimaryContactId(changes.getPrimaryContactId());
            existing.setEventType(changes.getEventType());
            existing.setDateFrom(changes.getDateFrom());
            existing.setDateTo(changes.getDateTo());
            existing.setExpectedPax(changes.getExpectedPax());
            existing.setGuaranteeDueDate(changes.getGuaranteeDueDate());
            if (StringUtils.hasText(changes.getCurrency())) {
                existing.setCurrency(changes.getCurrency());
            }
            if (changes.getOwnerUserId() != null) {
                existing.setOwnerUserId(changes.getOwnerUserId());
            }
            LocalDate previousDecision = existing.getDecisionDate();
            existing.setDecisionDate(changes.getDecisionDate());
            validateGuaranteedPax(existing, changes.getGuaranteedPax());
            existing.setGuaranteedPax(changes.getGuaranteedPax());
            if (existing.getStatus() == Event.Status.TENTATIVE) {
                requireFutureDecisionDate(existing);
            }
            validateHeader(tenantId, existing);
            validateFunctionsInsideDates(existing);
            Event saved = eventRepo.save(existing);
            if (saved.getStatus() == Event.Status.TENTATIVE && !java.util.Objects.equals(previousDecision, saved.getDecisionDate())) {
                reservationSync.syncEvent(saved);
            }
            existing = saved;
        }
        if (actual || changes.getActualPax() != null) {
            if (changes.getActualPax() != null && changes.getActualPax() < 0) {
                throw new IllegalArgumentException("actualPax must be >= 0");
            }
            existing.setActualPax(changes.getActualPax());
        }
        existing.setNotes(changes.getNotes());
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        Event saved = eventRepo.save(existing);
        itemPricing.recalculatePerPax(saved);
        return saved;
    }

    @Transactional
    public Event changeStatus(Long id, EventDtos.StatusChangeRequest request) {
        if (request == null || request.getStatus() == null) {
            throw new IllegalArgumentException("status is required");
        }
        Event event = requireEditable(id);
        if (request.getStatus().isClosed() && request.getStatus() != Event.Status.ACTUAL) {
            accessContext.require(CrmPermission.OPPORTUNITY_CLOSE);
        }
        return transition(event, request.getStatus(), request.getOutcomeReasonId(), request.getNote(),
                accessContext.currentUserId());
    }

    Event transition(Event event, Event.Status target, Long outcomeReasonId, String note, Long actorUserId) {
        Event.Status from = event.getStatus();
        if (from == target) {
            throw new IllegalStateException("Event is already " + target);
        }
        if (!TRANSITIONS.getOrDefault(from, Set.of()).contains(target)) {
            throw new IllegalStateException("Cannot move event from " + from + " to " + target);
        }
        switch (target) {
            case TENTATIVE -> {
                requireFutureDecisionDate(event);
                requireSpace(event, target);
            }
            case DEFINITE -> {
                requireSpace(event, target);
                if (definiteRequiresAcceptedQuote && !quoteRepo.existsByTenantIdAndEventIdAndStatus(
                        event.getTenantId(), event.getId(), SalesQuote.Status.ACCEPTED)) {
                    throw new IllegalStateException("An accepted quote is required before DEFINITE");
                }
            }
            case ACTUAL -> {
                if (event.getActualPax() == null) {
                    throw new IllegalStateException("actualPax is required before ACTUAL");
                }
                if (LocalDate.now().isBefore(event.getDateTo())) {
                    throw new IllegalStateException("Event can become ACTUAL only after its last day has started");
                }
            }
            default -> {
            }
        }
        Long reasonId = null;
        if (target == Event.Status.TURNED_DOWN || target == Event.Status.LOST || target == Event.Status.CANCELLED) {
            reasonId = requireReason(event.getTenantId(), outcomeReasonId, CrmOutcomeReason.Kind.valueOf(target.name())).getId();
        }
        event.setStatus(target);
        event.setOutcomeReasonId(reasonId);
        event.setOutcomeNote(reasonId != null ? note : null);
        Event saved = eventRepo.save(event);
        reservationSync.syncEvent(saved);
        if (target == Event.Status.ACTUAL) {
            itemPricing.recalculatePerPax(saved);
        }
        writeHistory(saved, from, target, reasonId, note, actorUserId);
        return saved;
    }

    public List<EventStatusHistory> history(Long eventId) {
        Event event = requireEvent(eventId);
        return historyRepo.findByTenantIdAndEventIdOrderByCreatedAtAscIdAsc(event.getTenantId(), event.getId());
    }

    /**
     * TENTATIVE events whose decision date passed become LOST; their holds already stopped blocking
     * at the decision date because allocation.expires_at is set to it.
     */
    @Scheduled(fixedDelayString = "${events.option-expiry-scan-ms:900000}")
    public void expireTentativeOptions() {
        for (Event candidate : eventRepo.findExpiredTentative(LocalDate.now())) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Event event = eventRepo.findById(candidate.getId()).orElseThrow();
                    if (event.getStatus() != Event.Status.TENTATIVE) {
                        return;
                    }
                    CrmOutcomeReason reason = optionExpiredReason(event.getTenantId());
                    transition(event, Event.Status.LOST, reason.getId(), "Option expired on " + event.getDecisionDate(), null);
                });
            } catch (RuntimeException ex) {
                log.warn("Could not expire tentative event {}: {}", candidate.getId(), ex.getMessage());
            }
        }
    }

    private CrmOutcomeReason optionExpiredReason(Long tenantId) {
        return outcomeRepo.findFirstByTenantIdAndKindAndCodeIgnoreCase(tenantId, CrmOutcomeReason.Kind.LOST, OPTION_EXPIRED_CODE)
                .orElseGet(() -> outcomeRepo.save(CrmOutcomeReason.builder()
                        .tenantId(tenantId)
                        .kind(CrmOutcomeReason.Kind.LOST)
                        .code(OPTION_EXPIRED_CODE)
                        .name("Option expired")
                        .displayOrder(999)
                        .active(true)
                        .build()));
    }

    private CrmOutcomeReason requireReason(Long tenantId, Long reasonId, CrmOutcomeReason.Kind kind) {
        if (reasonId == null) {
            throw new IllegalArgumentException("outcomeReasonId is required for " + kind);
        }
        CrmOutcomeReason reason = outcomeRepo.findByIdAndTenantId(reasonId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Outcome reason not found"));
        if (reason.getKind() != kind) {
            throw new IllegalArgumentException("Outcome reason kind must be " + kind);
        }
        return reason;
    }

    private void requireFutureDecisionDate(Event event) {
        if (event.getDecisionDate() == null) {
            throw new IllegalStateException("decisionDate is required for TENTATIVE");
        }
        if (event.getDecisionDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("decisionDate must not be in the past");
        }
    }

    /** Functions without a space (e.g. a coffee break in the foyer) hold nothing, but the event must hold at least one. */
    private void requireSpace(Event event, Event.Status target) {
        if (!functionRepo.existsByTenantIdAndEventIdAndResourceIdIsNotNull(event.getTenantId(), event.getId())) {
            throw new IllegalStateException("Assign a space to at least one function before " + target);
        }
    }

    private void validateGuaranteedPax(Event existing, Integer newGuaranteed) {
        if (newGuaranteed != null && newGuaranteed < 0) {
            throw new IllegalArgumentException("guaranteedPax must be >= 0");
        }
        Integer current = existing.getGuaranteedPax();
        LocalDate due = existing.getGuaranteeDueDate();
        if (current != null && due != null && LocalDate.now().isAfter(due)
                && (newGuaranteed == null || newGuaranteed < current)) {
            throw new IllegalStateException("Guaranteed pax cannot be reduced after " + due);
        }
    }

    private void validateHeader(Long tenantId, Event event) {
        if (!StringUtils.hasText(event.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (event.getAccountId() == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        accountRepo.findByIdAndTenantId(event.getAccountId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + event.getAccountId()));
        if (event.getPrimaryContactId() != null) {
            contactRepo.findByIdAndTenantId(event.getPrimaryContactId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + event.getPrimaryContactId()));
        }
        if (event.getDateFrom() == null || event.getDateTo() == null) {
            throw new IllegalArgumentException("dateFrom and dateTo are required");
        }
        if (event.getDateTo().isBefore(event.getDateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        if (event.getExpectedPax() != null && event.getExpectedPax() < 0) {
            throw new IllegalArgumentException("expectedPax must be >= 0");
        }
    }

    private void validateFunctionsInsideDates(Event event) {
        if (event.getId() == null) {
            return;
        }
        for (EventFunction function : functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(
                event.getTenantId(), event.getId())) {
            LocalDate day = function.getStartsAt().toLocalDate();
            if (day.isBefore(event.getDateFrom()) || day.isAfter(event.getDateTo())) {
                throw new IllegalArgumentException("Function on " + day + " falls outside the event dates");
            }
        }
    }

    private void writeHistory(Event event, Event.Status from, Event.Status to, Long reasonId, String note, Long actor) {
        historyRepo.save(EventStatusHistory.builder()
                .tenantId(event.getTenantId())
                .eventId(event.getId())
                .fromStatus(from)
                .toStatus(to)
                .outcomeReasonId(reasonId)
                .note(note)
                .changedBy(actor)
                .build());
    }

    private static String text(JsonNode attrs, String key) {
        if (attrs == null || !attrs.hasNonNull(key)) {
            return null;
        }
        String value = attrs.get(key).asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static Integer intValue(JsonNode attrs, String key) {
        if (attrs == null || !attrs.hasNonNull(key)) {
            return null;
        }
        JsonNode node = attrs.get(key);
        if (node.isNumber()) {
            return node.intValue();
        }
        try {
            return Integer.parseInt(node.asText().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static LocalDate parseDate(JsonNode attrs, String key) {
        String value = text(attrs, key);
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String firstText(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }
}
