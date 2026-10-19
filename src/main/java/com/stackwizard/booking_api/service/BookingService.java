package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.dto.BookingDtos;
import com.stackwizard.booking_api.model.CrmLookup;
import com.stackwizard.booking_api.model.CrmOpportunity;
import com.stackwizard.booking_api.model.CrmOutcomeReason;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.BookingFunction;
import com.stackwizard.booking_api.model.BookingStatus;
import com.stackwizard.booking_api.model.BookingStatusHistory;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.CrmOpportunityRepository;
import com.stackwizard.booking_api.repository.CrmOutcomeReasonRepository;
import com.stackwizard.booking_api.repository.BookingFunctionRepository;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.BookingStatusHistoryRepository;
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
import java.util.List;
import java.util.Optional;

@Service
public class BookingService {
    private static final Logger log = LoggerFactory.getLogger(BookingService.class);
    static final String OPTION_EXPIRED_CODE = "OPTION_EXPIRED";
    private static final LocalDate MIN_DATE = LocalDate.of(1900, 1, 1);
    private static final LocalDate MAX_DATE = LocalDate.of(9999, 12, 31);

    private final BookingRepository bookingRepo;
    private final BookingStatusHistoryRepository historyRepo;
    private final BookingFunctionRepository functionRepo;
    private final CrmOutcomeReasonRepository outcomeRepo;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final CrmOpportunityRepository opportunityRepo;
    private final BookingReservationSync reservationSync;
    private final BookingItemPricing itemPricing;
    private final CrmAccessContext accessContext;
    private final CrmTeamDirectory teamDirectory;
    private final TransactionTemplate transactionTemplate;
    private final SalesQuoteRepository quoteRepo;
    private final TenantHierarchy hierarchy;
    private final CrmLookupService lookupService;
    private final BookingStatusService statusService;
    private final boolean definiteRequiresAcceptedQuote;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public BookingService(BookingRepository bookingRepo,
                        BookingStatusHistoryRepository historyRepo,
                        BookingFunctionRepository functionRepo,
                        CrmOutcomeReasonRepository outcomeRepo,
                        CrmAccountRepository accountRepo,
                        CrmContactRepository contactRepo,
                        CrmOpportunityRepository opportunityRepo,
                        BookingReservationSync reservationSync,
                        BookingItemPricing itemPricing,
                        CrmAccessContext accessContext,
                        CrmTeamDirectory teamDirectory,
                        PlatformTransactionManager transactionManager,
                        SalesQuoteRepository quoteRepo,
                        TenantHierarchy hierarchy,
                        CrmLookupService lookupService,
                        BookingStatusService statusService,
                        @Value("${crm.events.definite-requires-accepted-quote:true}") boolean definiteRequiresAcceptedQuote) {
        this.bookingRepo = bookingRepo;
        this.historyRepo = historyRepo;
        this.functionRepo = functionRepo;
        this.outcomeRepo = outcomeRepo;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.opportunityRepo = opportunityRepo;
        this.reservationSync = reservationSync;
        this.itemPricing = itemPricing;
        this.accessContext = accessContext;
        this.teamDirectory = teamDirectory;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.quoteRepo = quoteRepo;
        this.hierarchy = hierarchy;
        this.lookupService = lookupService;
        this.statusService = statusService;
        this.definiteRequiresAcceptedQuote = definiteRequiresAcceptedQuote;
    }

    public List<Booking> findAll(Booking.Status status, Long accountId, LocalDate from, LocalDate to) {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return bookingRepo.findScoped(TenantResolver.requireOrgTenantId(), status, accountId,
                from != null ? from : MIN_DATE, to != null ? to : MAX_DATE,
                scope.all(), scope.own(), scope.team(), scope.currentUserId(), scope.teamUserIds(), scope.teamIds(),
                scope.allHotels(), scope.propertyIds(), TenantResolver.selectedHotelId());
    }

    public Optional<Booking> findById(Long id) {
        accessContext.require(CrmPermission.EVENT_READ);
        return bookingRepo.findByIdAndTenantId(id, TenantResolver.requireOrgTenantId())
                .filter(e -> CrmOwnerScope.from(accessContext).allows(e.getOwnerUserId(), e.getTeamId(), e.getPropertyTenantId()));
    }

    /** Tenant-scoped lookup without the caller's CRM scope, for scheduled jobs and the client portal. */
    public Optional<Booking> findForSystem(Long tenantId, Long id) {
        return bookingRepo.findByIdAndTenantId(id, tenantId);
    }

    public List<Booking> forOpportunity(Long opportunityId) {
        accessContext.require(CrmPermission.EVENT_READ);
        CrmOwnerScope scope = CrmOwnerScope.from(accessContext);
        return bookingRepo.findByTenantIdAndOpportunityIdOrderByDateFromAsc(TenantResolver.requireOrgTenantId(), opportunityId)
                .stream().filter(e -> scope.allows(e.getOwnerUserId(), e.getTeamId(), e.getPropertyTenantId())).toList();
    }

    public Booking requireBooking(Long id) {
        return findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    /**
     * Booking that may still be changed; closed outcomes are read-only.
     * Status moves stay allowed through {@link #changeStatus}; content edits use {@link #requireContentEditable}.
     */
    public Booking requireEditable(Long id) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Booking booking = requireBooking(id);
        if (booking.getStatus() == Booking.Status.TURNED_DOWN
                || booking.getStatus() == Booking.Status.LOST
                || booking.getStatus() == Booking.Status.CANCELLED) {
            throw new IllegalStateException("Booking is " + booking.getStatus() + " and can no longer be changed");
        }
        return booking;
    }

    /** True once the booking is Definite or later — space, rooms, catering and quote content are frozen. */
    public static boolean isContentLocked(Booking booking) {
        if (booking == null || booking.getStatus() == null) {
            return false;
        }
        return switch (booking.getStatus()) {
            case DEFINITE, ACTUAL, TURNED_DOWN, LOST, CANCELLED -> true;
            default -> false;
        };
    }

    /**
     * Booking whose plan (functions, rooms, items, quote lines) may still be edited.
     * Locked from {@link Booking.Status#DEFINITE} onward; status transitions still use {@link #requireEditable}.
     */
    public Booking requireContentEditable(Long id) {
        Booking booking = requireEditable(id);
        if (isContentLocked(booking)) {
            throw new IllegalStateException("Booking is " + booking.getStatus()
                    + " and its content can no longer be changed");
        }
        return booking;
    }

    @Transactional
    public Booking create(Booking booking) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        booking.setId(null);
        booking.setTenantId(tenantId);
        BookingStatus start = statusService.rules(tenantId).start();
        booking.setStatus(start.getPhase());
        booking.setStatusCode(start.getCode());
        booking.setOutcomeReasonId(null);
        booking.setOutcomeNote(null);
        booking.setActualPax(null);
        if (!StringUtils.hasText(booking.getCurrency())) {
            booking.setCurrency("EUR");
        }
        if (booking.getOwnerUserId() == null) {
            booking.setOwnerUserId(accessContext.currentUserId());
        }
        if (booking.getAttrs() == null) {
            booking.setAttrs(objectMapper.createObjectNode());
        }
        String carriedEventType = null;
        if (booking.getOpportunityId() != null) {
            CrmOpportunity opportunity = opportunityRepo.findByIdAndTenantId(booking.getOpportunityId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Opportunity not found: " + booking.getOpportunityId()));
            carriedEventType = text(opportunity.getAttrs(), "eventType");
            if (booking.getAccountId() == null) {
                booking.setAccountId(opportunity.getAccountId());
            }
            if (booking.getTeamId() == null) {
                booking.setTeamId(opportunity.getTeamId());
            }
            if (booking.getPropertyTenantId() == null) {
                booking.setPropertyTenantId(opportunity.getPropertyTenantId());
            }
        }
        requireHotelOfChain(tenantId, booking.getPropertyTenantId());
        booking.setEventType(lookupService.requireCode(tenantId, CrmLookup.Kind.EVENT_TYPE, booking.getEventType(), carriedEventType));
        Long accountTeam = booking.getAccountId() == null ? null
                : accountRepo.findByIdAndTenantId(booking.getAccountId(), tenantId).map(a -> a.getTeamId()).orElse(null);
        booking.setTeamId(teamDirectory.resolveTeam(tenantId, booking.getOwnerUserId(), booking.getTeamId(), accountTeam));
        teamDirectory.requireAssignable(tenantId, booking.getOwnerUserId(), booking.getTeamId());
        booking.setPropertyTenantId(teamDirectory.resolveProperty(tenantId, booking.getTeamId(), booking.getPropertyTenantId()));
        validateHeader(tenantId, booking);
        Booking saved = bookingRepo.save(booking);
        writeHistory(saved, null, null, null, "created", accessContext.currentUserId());
        return saved;
    }

    /**
     * New INQUIRY booking prefilled from the opportunity intake (attrs title/eventType/startDate/endDate/pax).
     */
    @Transactional
    public Booking createFromOpportunity(Long opportunityId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        Long tenantId = TenantResolver.requireOrgTenantId();
        CrmOpportunity opportunity = opportunityRepo.findByIdAndTenantId(opportunityId, tenantId)
                .filter(o -> CrmOwnerScope.from(accessContext).allows(o.getOwnerUserId(), o.getTeamId(), o.getPropertyTenantId()))
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
        Booking booking = Booking.builder()
                .accountId(opportunity.getAccountId())
                .primaryContactId(opportunity.getPrimaryContactId())
                .opportunityId(opportunity.getId())
                .propertyTenantId(opportunity.getPropertyTenantId())
                .name(firstText(text(attrs, "title"), opportunity.getName()))
                .eventType(text(attrs, "eventType"))
                .dateFrom(from)
                .dateTo(to)
                .expectedPax(intValue(attrs, "pax"))
                .currency(opportunity.getCurrency())
                .ownerUserId(opportunity.getOwnerUserId())
                .teamId(opportunity.getTeamId())
                .notes(text(attrs, "notes"))
                .build();
        return create(booking);
    }

    @Transactional
    public Booking update(Long id, Booking changes) {
        Booking existing = requireEditable(id);
        Long tenantId = existing.getTenantId();
        boolean actual = existing.getStatus() == Booking.Status.ACTUAL;
        boolean contentLocked = isContentLocked(existing);
        if (!contentLocked) {
            existing.setName(changes.getName());
            existing.setAccountId(changes.getAccountId() != null ? changes.getAccountId() : existing.getAccountId());
            existing.setPrimaryContactId(changes.getPrimaryContactId());
            Long previousProperty = existing.getPropertyTenantId();
            changeProperty(existing, changes.getPropertyTenantId());
            existing.setEventType(lookupService.requireCode(tenantId, CrmLookup.Kind.EVENT_TYPE,
                    changes.getEventType(), existing.getEventType()));
            existing.setDateFrom(changes.getDateFrom());
            existing.setDateTo(changes.getDateTo());
            existing.setExpectedPax(changes.getExpectedPax());
            existing.setGuaranteeDueDate(changes.getGuaranteeDueDate());
            if (StringUtils.hasText(changes.getCurrency())) {
                existing.setCurrency(changes.getCurrency());
            }
            Long owner = changes.getOwnerUserId() != null ? changes.getOwnerUserId() : existing.getOwnerUserId();
            boolean ownerChanged = !java.util.Objects.equals(owner, existing.getOwnerUserId());
            Long team = changes.getTeamId() != null ? changes.getTeamId()
                    : ownerChanged ? teamDirectory.resolveTeam(tenantId, owner, null, existing.getTeamId())
                    : existing.getTeamId();
            if (ownerChanged || !java.util.Objects.equals(team, existing.getTeamId())) {
                teamDirectory.requireAssignable(tenantId, owner, team);
            }
            if (!java.util.Objects.equals(previousProperty, existing.getPropertyTenantId()) || ownerChanged
                    || !java.util.Objects.equals(team, existing.getTeamId())) {
                changeProperty(existing, teamDirectory.resolveProperty(tenantId, team, existing.getPropertyTenantId()));
            }
            existing.setOwnerUserId(owner);
            existing.setTeamId(team);
            LocalDate previousDecision = existing.getDecisionDate();
            existing.setDecisionDate(changes.getDecisionDate());
            validateGuaranteedPax(existing, changes.getGuaranteedPax());
            existing.setGuaranteedPax(changes.getGuaranteedPax());
            if (existing.getStatus() == Booking.Status.TENTATIVE) {
                requireFutureDecisionDate(existing);
            }
            validateHeader(tenantId, existing);
            validateFunctionsInsideDates(existing);
            Booking saved = bookingRepo.save(existing);
            if (saved.getStatus() == Booking.Status.TENTATIVE && !java.util.Objects.equals(previousDecision, saved.getDecisionDate())) {
                reservationSync.syncBooking(saved);
            }
            existing = saved;
        }
        if (actual) {
            if (changes.getActualPax() != null && changes.getActualPax() < 0) {
                throw new IllegalArgumentException("actualPax must be >= 0");
            }
            existing.setActualPax(changes.getActualPax());
        }
        existing.setNotes(changes.getNotes());
        if (changes.getAttrs() != null) {
            existing.setAttrs(changes.getAttrs());
        }
        Booking saved = bookingRepo.save(existing);
        itemPricing.recalculatePerPax(saved);
        return saved;
    }

    @Transactional
    public Booking changeStatus(Long id, BookingDtos.StatusChangeRequest request) {
        if (request == null || request.getStatus() == null && !StringUtils.hasText(request.getStatusCode())) {
            throw new IllegalArgumentException("status is required");
        }
        Booking booking = requireEditable(id);
        BookingStatus target = statusService.rules(booking.getTenantId())
                .target(booking, request.getStatusCode(), request.getStatus());
        if (target.getPhase().isClosed() && target.getPhase() != Booking.Status.ACTUAL) {
            accessContext.require(CrmPermission.OPPORTUNITY_CLOSE);
        }
        return transition(booking, target, request.getOutcomeReasonId(), request.getNote(),
                accessContext.currentUserId());
    }

    /** Statuses the booking may move to now. */
    @Transactional
    public List<BookingStatus> nextStatuses(Long id) {
        Booking booking = requireBooking(id);
        return statusService.rules(booking.getTenantId()).next(booking);
    }

    /**
     * A deal entered a stage that sets an booking status: its open bookings move there. A move the status flow does not
     * allow stops the stage change.
     */
    @Transactional
    public void applyStageStatus(Long orgTenantId, Long opportunityId, String statusCode, String stageName) {
        BookingStatusRules rules = statusService.rules(orgTenantId);
        for (Booking booking : bookingRepo.findByTenantIdAndOpportunityIdOrderByDateFromAsc(orgTenantId, opportunityId)) {
            if (booking.getStatus().isClosed() || statusCode.equalsIgnoreCase(BookingStatusRules.codeOf(booking))) {
                continue;
            }
            accessContext.require(CrmPermission.EVENT_WRITE);
            BookingStatus target;
            try {
                target = rules.target(booking, statusCode, null);
            } catch (IllegalStateException ex) {
                throw new IllegalStateException("Booking \"" + booking.getName() + "\": " + ex.getMessage(), ex);
            }
            transition(booking, target, null, "Stage " + stageName, accessContext.currentUserId());
        }
    }

    /** Phase rules (decision date, space, quote, final pax, reason) apply only when the phase changes. */
    Booking transition(Booking booking, BookingStatus target, Long outcomeReasonId, String note, Long actorUserId) {
        Booking.Status from = booking.getStatus();
        String fromCode = BookingStatusRules.codeOf(booking);
        Booking.Status phase = target.getPhase();
        boolean phaseChange = phase != from;
        if (phaseChange) {
            switch (phase) {
                case TENTATIVE -> {
                    requireFutureDecisionDate(booking);
                    requirePlacedNeed(booking, phase);
                }
                case DEFINITE -> {
                    requirePlacedNeed(booking, phase);
                    if (definiteRequiresAcceptedQuote && !quoteRepo.existsByTenantIdAndBookingIdAndStatus(
                            booking.getTenantId(), booking.getId(), SalesQuote.Status.ACCEPTED)) {
                        throw new IllegalStateException("An accepted quote is required before DEFINITE");
                    }
                }
                case ACTUAL -> {
                    if (booking.getActualPax() == null) {
                        throw new IllegalStateException("actualPax is required before ACTUAL");
                    }
                    if (LocalDate.now().isBefore(booking.getDateTo())) {
                        throw new IllegalStateException("Booking can become ACTUAL only after its last day has started");
                    }
                }
                default -> {
                }
            }
        }
        Long reasonId = null;
        if (phaseChange && (phase == Booking.Status.TURNED_DOWN || phase == Booking.Status.LOST || phase == Booking.Status.CANCELLED)) {
            reasonId = requireReason(booking.getTenantId(), outcomeReasonId, CrmOutcomeReason.Kind.valueOf(phase.name())).getId();
        }
        reservationSync.verifyOperaTransition(booking, target);
        booking.setStatus(phase);
        booking.setStatusCode(target.getCode());
        if (phaseChange) {
            booking.setOutcomeReasonId(reasonId);
            booking.setOutcomeNote(reasonId != null ? note : null);
        }
        Booking saved = bookingRepo.save(booking);
        if (phaseChange) {
            reservationSync.syncBooking(saved);
            if (phase == Booking.Status.ACTUAL) {
                itemPricing.recalculatePerPax(saved);
            }
        } else {
            reservationSync.pushOperaBlock(saved);
        }
        writeHistory(saved, from, fromCode, reasonId, note, actorUserId);
        return saved;
    }

    public List<BookingStatusHistory> history(Long bookingId) {
        Booking booking = requireBooking(bookingId);
        return historyRepo.findByTenantIdAndBookingIdOrderByCreatedAtAscIdAsc(booking.getTenantId(), booking.getId());
    }

    /**
     * TENTATIVE bookings whose decision date passed become LOST; their holds already stopped blocking
     * at the decision date because allocation.expires_at is set to it.
     */
    @Scheduled(fixedDelayString = "${events.option-expiry-scan-ms:900000}")
    public void expireTentativeOptions() {
        for (Booking candidate : bookingRepo.findExpiredTentative(LocalDate.now())) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    Booking booking = bookingRepo.findById(candidate.getId()).orElseThrow();
                    if (booking.getStatus() != Booking.Status.TENTATIVE) {
                        return;
                    }
                    CrmOutcomeReason reason = optionExpiredReason(booking.getTenantId());
                    transition(booking, statusService.rules(booking.getTenantId()).firstOfPhase(Booking.Status.LOST),
                            reason.getId(), "Option expired on " + booking.getDecisionDate(), null);
                });
            } catch (RuntimeException ex) {
                log.warn("Could not expire tentative booking {}: {}", candidate.getId(), ex.getMessage());
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

    private void requireFutureDecisionDate(Booking booking) {
        if (booking.getDecisionDate() == null) {
            throw new IllegalStateException("decisionDate is required for TENTATIVE");
        }
        if (booking.getDecisionDate().isBefore(LocalDate.now())) {
            throw new IllegalStateException("decisionDate must not be in the past");
        }
    }

    /**
     * Needs without a resource (a coffee break in the foyer, rooms not yet placed) hold nothing, but the booking must
     * hold at least one: a function in a space or a room block on a room type.
     */
    private void requirePlacedNeed(Booking booking, Booking.Status target) {
        if (!functionRepo.existsSpaceFunctionForBooking(booking.getTenantId(), booking.getId())
                && !reservationSync.hasPlacedRoomBlock(booking)) {
            throw new IllegalStateException("Place at least one need (a function in a space or rooms on a room type) before " + target);
        }
    }

    private void validateGuaranteedPax(Booking existing, Integer newGuaranteed) {
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

    private void validateHeader(Long tenantId, Booking booking) {
        if (!StringUtils.hasText(booking.getName())) {
            throw new IllegalArgumentException("name is required");
        }
        if (booking.getAccountId() == null) {
            throw new IllegalArgumentException("accountId is required");
        }
        accountRepo.findByIdAndTenantId(booking.getAccountId(), tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Account not found: " + booking.getAccountId()));
        if (booking.getPrimaryContactId() != null) {
            contactRepo.findByIdAndTenantId(booking.getPrimaryContactId(), tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Contact not found: " + booking.getPrimaryContactId()));
        }
        if (booking.getDateFrom() == null || booking.getDateTo() == null) {
            throw new IllegalArgumentException("dateFrom and dateTo are required");
        }
        if (booking.getDateTo().isBefore(booking.getDateFrom())) {
            throw new IllegalArgumentException("dateTo must not be before dateFrom");
        }
        if (booking.getExpectedPax() != null && booking.getExpectedPax() < 0) {
            throw new IllegalArgumentException("expectedPax must be >= 0");
        }
    }

    private void requireHotelOfChain(Long orgTenantId, Long propertyTenantId) {
        if (propertyTenantId != null) {
            hierarchy.requirePropertyOf(propertyTenantId, orgTenantId);
        }
    }

    /** Limiting an booking to a hotel is only possible while none of its functions is in another hotel. */
    private void changeProperty(Booking booking, Long propertyTenantId) {
        if (java.util.Objects.equals(booking.getPropertyTenantId(), propertyTenantId)) {
            return;
        }
        requireHotelOfChain(booking.getTenantId(), propertyTenantId);
        if (propertyTenantId != null) {
            boolean elsewhere = functionRepo.findForBooking(booking.getTenantId(), booking.getId()).stream()
                    .anyMatch(f -> !propertyTenantId.equals(f.getTenantId()));
            if (elsewhere) {
                throw new IllegalStateException("The booking has functions in other hotels; move or remove them first");
            }
        }
        booking.setPropertyTenantId(propertyTenantId);
    }

    private void validateFunctionsInsideDates(Booking booking) {
        if (booking.getId() == null) {
            return;
        }
        for (BookingFunction function : functionRepo.findForBooking(
                booking.getTenantId(), booking.getId())) {
            LocalDate day = function.getStartsAt().toLocalDate();
            if (day.isBefore(booking.getDateFrom()) || day.isAfter(booking.getDateTo())) {
                throw new IllegalArgumentException("Function on " + day + " falls outside the booking dates");
            }
        }
    }

    /** Records the move from {@code from}/{@code fromCode} to the booking's current status. */
    private void writeHistory(Booking booking, Booking.Status from, String fromCode, Long reasonId, String note, Long actor) {
        historyRepo.save(BookingStatusHistory.builder()
                .tenantId(booking.getTenantId())
                .bookingId(booking.getId())
                .fromStatus(from)
                .toStatus(booking.getStatus())
                .fromCode(fromCode)
                .toCode(booking.getStatusCode())
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
