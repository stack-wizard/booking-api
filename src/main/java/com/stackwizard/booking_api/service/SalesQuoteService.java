package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmAlert;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.EventFunction;
import com.stackwizard.booking_api.model.EventFunctionItem;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteApproval;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.model.SalesQuoteVersion;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.EventFunctionItemRepository;
import com.stackwizard.booking_api.repository.EventFunctionRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.SalesQuoteApprovalRepository;
import com.stackwizard.booking_api.repository.SalesQuoteLineRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.repository.SalesQuoteVersionRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Quote generated from an event's functions and items. Lines are a commercial copy that sales can discount;
 * a discount above the threshold needs a manager's approval before the quote can be sent.
 */
@Service
public class SalesQuoteService {
    private static final Logger log = LoggerFactory.getLogger(SalesQuoteService.class);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final Set<EventFunction.FunctionType> CATERING = EnumSet.of(
            EventFunction.FunctionType.COFFEE_BREAK, EventFunction.FunctionType.LUNCH,
            EventFunction.FunctionType.DINNER, EventFunction.FunctionType.RECEPTION);

    private final SalesQuoteRepository quoteRepo;
    private final SalesQuoteLineRepository lineRepo;
    private final SalesQuoteVersionRepository versionRepo;
    private final SalesQuoteApprovalRepository approvalRepo;
    private final EventService eventService;
    private final EventFunctionRepository functionRepo;
    private final EventFunctionItemRepository itemRepo;
    private final EventReservationSync reservationSync;
    private final ProductRepository productRepo;
    private final CrmAccountRepository accountRepo;
    private final PortalTokenService portalTokenService;
    private final CrmAlertService alertService;
    private final CrmAccessContext accessContext;
    private final TransactionTemplate transactionTemplate;
    private final BigDecimal approvalThreshold;
    private final int validityDays;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SalesQuoteService(SalesQuoteRepository quoteRepo,
                             SalesQuoteLineRepository lineRepo,
                             SalesQuoteVersionRepository versionRepo,
                             SalesQuoteApprovalRepository approvalRepo,
                             EventService eventService,
                             EventFunctionRepository functionRepo,
                             EventFunctionItemRepository itemRepo,
                             EventReservationSync reservationSync,
                             ProductRepository productRepo,
                             CrmAccountRepository accountRepo,
                             PortalTokenService portalTokenService,
                             CrmAlertService alertService,
                             CrmAccessContext accessContext,
                             PlatformTransactionManager transactionManager,
                             @Value("${crm.quotes.approval-threshold-percent:10}") BigDecimal approvalThreshold,
                             @Value("${crm.quotes.validity-days:14}") int validityDays) {
        this.quoteRepo = quoteRepo;
        this.lineRepo = lineRepo;
        this.versionRepo = versionRepo;
        this.approvalRepo = approvalRepo;
        this.eventService = eventService;
        this.functionRepo = functionRepo;
        this.itemRepo = itemRepo;
        this.reservationSync = reservationSync;
        this.productRepo = productRepo;
        this.accountRepo = accountRepo;
        this.portalTokenService = portalTokenService;
        this.alertService = alertService;
        this.accessContext = accessContext;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.approvalThreshold = approvalThreshold;
        this.validityDays = validityDays;
    }

    @Transactional(readOnly = true)
    public List<SalesQuote> forEvent(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        return quoteRepo.findByTenantIdAndEventIdOrderByIdDesc(event.getTenantId(), event.getId());
    }

    @Transactional(readOnly = true)
    public List<SalesQuote> pendingApprovals() {
        accessContext.require(CrmPermission.QUOTE_APPROVE);
        return quoteRepo.findByTenantIdAndStatusOrderByIdDesc(TenantResolver.requireTenantId(), SalesQuote.Status.PENDING_APPROVAL);
    }

    @Transactional(readOnly = true)
    public SalesDtos.QuoteView view(Long quoteId) {
        SalesQuote quote = requireQuote(quoteId);
        Event event = eventService.requireEvent(quote.getEventId());
        return view(quote, portalTokenService.activeToken(event).map(portalTokenService::portalUrl).orElse(null));
    }

    SalesDtos.QuoteView view(SalesQuote quote, String portalUrl) {
        List<SalesQuoteLine> lines = lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId());
        List<SalesDtos.VersionSummary> versions = versionRepo.findByTenantIdAndQuoteIdOrderByVersionDesc(quote.getTenantId(), quote.getId())
                .stream().map(SalesDtos.VersionSummary::of).toList();
        List<SalesQuoteApproval> approvals = approvalRepo.findByTenantIdAndQuoteIdOrderByCreatedAtDescIdDesc(quote.getTenantId(), quote.getId());
        return new SalesDtos.QuoteView(quote, lines, groupTotals(lines), versions, approvals, approvalThreshold,
                needsApproval(quote), portalUrl);
    }

    /** New DRAFT from the event; other open quotes of the event become SUPERSEDED. */
    @Transactional
    public SalesQuote create(Long eventId) {
        Event event = eventService.requireEditable(eventId);
        if (event.getStatus() == Event.Status.ACTUAL) {
            throw new IllegalStateException("Quotes cannot be created for an ACTUAL event");
        }
        Long tenantId = event.getTenantId();
        if (quoteRepo.existsByTenantIdAndEventIdAndStatus(tenantId, event.getId(), SalesQuote.Status.ACCEPTED)) {
            throw new IllegalStateException("Event already has an accepted quote");
        }
        List<SalesQuote> open = quoteRepo.findByTenantIdAndEventIdAndStatusIn(tenantId, event.getId(), SalesQuote.Status.OPEN);
        for (SalesQuote previous : open) {
            previous.setStatus(SalesQuote.Status.SUPERSEDED);
            closePendingApproval(previous, "Superseded by a new quote");
        }
        quoteRepo.saveAll(open);

        List<SalesQuoteLine> lines = linesFromEvent(event);
        if (lines.isEmpty()) {
            throw new IllegalStateException("Event has nothing to quote; add functions with spaces or items first");
        }
        int year = LocalDate.now().getYear();
        String prefix = "Q" + year + "-";
        long seq = quoteRepo.countByNumberPrefix(tenantId, prefix + "%") + 1;
        SalesQuote quote = SalesQuote.builder()
                .tenantId(tenantId)
                .eventId(event.getId())
                .opportunityId(event.getOpportunityId())
                .accountId(event.getAccountId())
                .quoteNumber(prefix + String.format("%04d", seq))
                .status(SalesQuote.Status.DRAFT)
                .currency(event.getCurrency())
                .validUntil(defaultValidUntil(event))
                .version(0)
                .build();
        recalculate(quote, lines);
        SalesQuote saved = quoteRepo.save(quote);
        int order = 0;
        for (SalesQuoteLine line : lines) {
            line.setQuoteId(saved.getId());
            line.setDisplayOrder(order++);
        }
        lineRepo.saveAll(lines);
        return saved;
    }

    @Transactional
    public SalesQuote updateHeader(Long quoteId, SalesDtos.QuoteHeaderRequest request) {
        SalesQuote quote = requireEditableQuote(quoteId);
        if (request.validUntil() != null && request.validUntil().isBefore(LocalDate.now())) {
            throw new IllegalArgumentException("validUntil must not be in the past");
        }
        quote.setValidUntil(request.validUntil());
        quote.setNotes(blankToNull(request.notes()));
        quote.setTerms(blankToNull(request.terms()));
        return quoteRepo.save(quote);
    }

    @Transactional
    public SalesQuoteLine addLine(Long quoteId, SalesDtos.QuoteLineRequest request) {
        SalesQuote quote = requireEditableQuote(quoteId);
        List<SalesQuoteLine> lines = lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId());
        SalesQuoteLine line = SalesQuoteLine.builder()
                .tenantId(quote.getTenantId())
                .quoteId(quote.getId())
                .displayOrder(lines.stream().mapToInt(SalesQuoteLine::getDisplayOrder).max().orElse(-1) + 1)
                .build();
        applyLine(quote.getTenantId(), line, request, true);
        SalesQuoteLine saved = lineRepo.save(line);
        lines.add(saved);
        touchAfterEdit(quote, lines);
        return saved;
    }

    @Transactional
    public SalesQuoteLine updateLine(Long quoteId, Long lineId, SalesDtos.QuoteLineRequest request) {
        SalesQuote quote = requireEditableQuote(quoteId);
        SalesQuoteLine line = lineRepo.findByIdAndTenantIdAndQuoteId(lineId, quote.getTenantId(), quote.getId())
                .orElseThrow(() -> new IllegalArgumentException("Quote line not found: " + lineId));
        applyLine(quote.getTenantId(), line, request, false);
        SalesQuoteLine saved = lineRepo.save(line);
        touchAfterEdit(quote, lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId()));
        return saved;
    }

    @Transactional
    public void deleteLine(Long quoteId, Long lineId) {
        SalesQuote quote = requireEditableQuote(quoteId);
        SalesQuoteLine line = lineRepo.findByIdAndTenantIdAndQuoteId(lineId, quote.getTenantId(), quote.getId())
                .orElseThrow(() -> new IllegalArgumentException("Quote line not found: " + lineId));
        lineRepo.delete(line);
        lineRepo.flush();
        touchAfterEdit(quote, lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId()));
    }

    @Transactional
    public SalesQuote requestApproval(Long quoteId, String note) {
        SalesQuote quote = requireQuoteForWrite(quoteId);
        if (quote.getStatus() != SalesQuote.Status.DRAFT) {
            throw new IllegalStateException("Only a DRAFT quote can be submitted for approval, not " + quote.getStatus());
        }
        if (!needsApproval(quote)) {
            throw new IllegalStateException("Discount " + percent(quote.getDiscountPercent()) + "% is within "
                    + percent(approvalThreshold) + "%; no approval needed");
        }
        approvalRepo.save(SalesQuoteApproval.builder()
                .tenantId(quote.getTenantId())
                .quoteId(quote.getId())
                .status(SalesQuoteApproval.Status.PENDING)
                .discountPercent(quote.getDiscountPercent())
                .thresholdPercent(approvalThreshold)
                .requestedBy(accessContext.currentUserId())
                .note(blankToNull(note))
                .build());
        quote.setStatus(SalesQuote.Status.PENDING_APPROVAL);
        return quoteRepo.save(quote);
    }

    @Transactional
    public SalesQuote decideApproval(Long quoteId, boolean approve, String note) {
        accessContext.require(CrmPermission.QUOTE_APPROVE);
        SalesQuote quote = requireQuoteForWrite(quoteId);
        if (quote.getStatus() != SalesQuote.Status.PENDING_APPROVAL) {
            throw new IllegalStateException("Quote is not waiting for approval");
        }
        SalesQuoteApproval approval = approvalRepo.findFirstByTenantIdAndQuoteIdAndStatus(
                        quote.getTenantId(), quote.getId(), SalesQuoteApproval.Status.PENDING)
                .orElseThrow(() -> new IllegalStateException("No pending approval for the quote"));
        approval.setStatus(approve ? SalesQuoteApproval.Status.APPROVED : SalesQuoteApproval.Status.REJECTED);
        approval.setDecidedBy(accessContext.currentUserId());
        approval.setDecidedAt(OffsetDateTime.now());
        if (StringUtils.hasText(note)) {
            approval.setNote(approval.getNote() == null ? note.trim() : approval.getNote() + "\n" + note.trim());
        }
        approvalRepo.save(approval);
        quote.setStatus(approve ? SalesQuote.Status.APPROVED : SalesQuote.Status.DRAFT);
        return quoteRepo.save(quote);
    }

    /** Freezes the current lines as the next version and opens the client portal link. */
    @Transactional
    public SalesDtos.QuoteView send(Long quoteId) {
        SalesQuote quote = requireQuoteForWrite(quoteId);
        Event event = eventService.requireEditable(quote.getEventId());
        if (quote.getStatus() == SalesQuote.Status.DRAFT && needsApproval(quote)) {
            throw new IllegalStateException("Discount " + percent(quote.getDiscountPercent()) + "% exceeds "
                    + percent(approvalThreshold) + "%; request approval first");
        }
        if (quote.getStatus() != SalesQuote.Status.DRAFT && quote.getStatus() != SalesQuote.Status.APPROVED) {
            throw new IllegalStateException("Quote cannot be sent from " + quote.getStatus());
        }
        if (quote.getValidUntil() != null && quote.getValidUntil().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Quote validity ended on " + quote.getValidUntil() + "; extend it first");
        }
        List<SalesQuoteLine> lines = lineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(quote.getTenantId(), quote.getId());
        if (lines.isEmpty()) {
            throw new IllegalStateException("Quote has no lines");
        }
        int version = quote.getVersion() + 1;
        CrmAccount account = accountRepo.findByIdAndTenantId(quote.getAccountId(), quote.getTenantId()).orElse(null);
        versionRepo.save(SalesQuoteVersion.builder()
                .tenantId(quote.getTenantId())
                .quoteId(quote.getId())
                .version(version)
                .snapshot(snapshot(quote, event, account, lines, version))
                .totalOffered(quote.getTotalOffered())
                .sentBy(accessContext.currentUserId())
                .build());
        quote.setVersion(version);
        quote.setStatus(SalesQuote.Status.SENT);
        quote.setSentAt(OffsetDateTime.now());
        SalesQuote saved = quoteRepo.save(quote);
        String url = portalTokenService.portalUrl(portalTokenService.ensureToken(event, accessContext.currentUserId()));
        return view(saved, url);
    }

    /** SENT back to DRAFT to change lines; the next send becomes a new version. */
    @Transactional
    public SalesQuote revise(Long quoteId) {
        SalesQuote quote = requireQuoteForWrite(quoteId);
        if (quote.getStatus() != SalesQuote.Status.SENT && quote.getStatus() != SalesQuote.Status.EXPIRED) {
            throw new IllegalStateException("Only a SENT or EXPIRED quote can be revised, not " + quote.getStatus());
        }
        if (quote.getStatus() == SalesQuote.Status.EXPIRED) {
            quote.setValidUntil(defaultValidUntil(eventService.requireEvent(quote.getEventId())));
        }
        quote.setStatus(SalesQuote.Status.DRAFT);
        return quoteRepo.save(quote);
    }

    /** Accept or reject on the client's behalf (phone, e-mail). */
    @Transactional
    public SalesQuote decide(Long quoteId, boolean accept, SalesDtos.DecisionRequest request) {
        SalesQuote quote = requireQuoteForWrite(quoteId);
        eventService.requireEditable(quote.getEventId());
        return applyDecision(quote, accept, request != null ? request.name() : null, request != null ? request.note() : null, false);
    }

    SalesQuote applyDecision(SalesQuote quote, boolean accept, String name, String note, boolean byClient) {
        if (quote.getStatus() != SalesQuote.Status.SENT) {
            throw new IllegalStateException("Only a SENT quote can be " + (accept ? "accepted" : "rejected") + ", it is " + quote.getStatus());
        }
        if (accept && quote.getValidUntil() != null && quote.getValidUntil().isBefore(LocalDate.now())) {
            throw new IllegalStateException("Quote expired on " + quote.getValidUntil());
        }
        quote.setStatus(accept ? SalesQuote.Status.ACCEPTED : SalesQuote.Status.REJECTED);
        quote.setDecidedAt(OffsetDateTime.now());
        quote.setDecidedByName(blankToNull(name));
        quote.setDecisionNote(blankToNull(note));
        SalesQuote saved = quoteRepo.save(quote);
        if (byClient) {
            Event event = eventService.findForSystem(saved.getTenantId(), saved.getEventId()).orElse(null);
            alertService.raise(CrmAlert.builder()
                    .tenantId(saved.getTenantId())
                    .kind(CrmAlert.Kind.QUOTE_DECIDED)
                    .eventId(saved.getEventId())
                    .quoteId(saved.getId())
                    .assignedTo(event != null ? event.getOwnerUserId() : null)
                    .message("Client " + (accept ? "accepted" : "rejected") + " quote " + saved.getQuoteNumber()
                            + (event != null ? " for " + event.getName() : ""))
                    .dueDate(LocalDate.now())
                    .dedupeKey("QUOTE_DECIDED:" + saved.getId() + ":" + saved.getVersion())
                    .build());
        }
        return saved;
    }

    /** SENT quotes past valid_until become EXPIRED and the owner is alerted. */
    @Scheduled(fixedDelayString = "${crm.quotes.expiry-scan-ms:3600000}", initialDelayString = "${crm.quotes.expiry-initial-delay-ms:60000}")
    public void expireSentQuotes() {
        for (SalesQuote candidate : quoteRepo.findSentExpiredBefore(LocalDate.now())) {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    SalesQuote quote = quoteRepo.findById(candidate.getId()).orElseThrow();
                    if (quote.getStatus() != SalesQuote.Status.SENT) {
                        return;
                    }
                    quote.setStatus(SalesQuote.Status.EXPIRED);
                    quoteRepo.save(quote);
                    Event event = eventService.findForSystem(quote.getTenantId(), quote.getEventId()).orElse(null);
                    alertService.raise(CrmAlert.builder()
                            .tenantId(quote.getTenantId())
                            .kind(CrmAlert.Kind.QUOTE_EXPIRED)
                            .eventId(quote.getEventId())
                            .quoteId(quote.getId())
                            .assignedTo(event != null ? event.getOwnerUserId() : null)
                            .message("Quote " + quote.getQuoteNumber() + " expired on " + quote.getValidUntil()
                                    + (event != null ? " (" + event.getName() + ")" : ""))
                            .dueDate(quote.getValidUntil())
                            .dedupeKey("QUOTE_EXPIRED:" + quote.getId() + ":" + quote.getVersion())
                            .build());
                });
            } catch (RuntimeException ex) {
                log.warn("Could not expire quote {}: {}", candidate.getId(), ex.getMessage());
            }
        }
    }

    public boolean hasAcceptedQuote(Event event) {
        return quoteRepo.existsByTenantIdAndEventIdAndStatus(event.getTenantId(), event.getId(), SalesQuote.Status.ACCEPTED);
    }

    public SalesQuote requireQuote(Long quoteId) {
        accessContext.require(CrmPermission.EVENT_READ);
        SalesQuote quote = quoteRepo.findByIdAndTenantId(quoteId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote not found"));
        eventService.requireEvent(quote.getEventId());
        return quote;
    }

    boolean needsApproval(SalesQuote quote) {
        return quote.getDiscountPercent() != null && quote.getDiscountPercent().compareTo(approvalThreshold) > 0;
    }

    List<SalesQuoteLine> linesFromEvent(Event event) {
        List<EventFunction> functions = functionRepo.findByTenantIdAndEventIdOrderByStartsAtAscDisplayOrderAscIdAsc(
                event.getTenantId(), event.getId());
        List<Long> functionIds = functions.stream().map(EventFunction::getId).toList();
        Map<Long, Reservation> rentByFunction = reservationSync.activeLines(event.getTenantId(), functionIds).stream()
                .collect(Collectors.toMap(Reservation::getEventFunctionId, Function.identity(), (a, b) -> a));
        Map<Long, List<EventFunctionItem>> itemsByFunction = functionIds.isEmpty() ? Map.of()
                : itemRepo.findByTenantIdAndEventFunctionIdIn(event.getTenantId(), functionIds).stream()
                .collect(Collectors.groupingBy(EventFunctionItem::getEventFunctionId));
        List<Long> productIds = new ArrayList<>();
        rentByFunction.values().forEach(r -> productIds.add(r.getProductId()));
        itemsByFunction.values().forEach(list -> list.forEach(i -> productIds.add(i.getProductId())));
        Map<Long, Product> products = productRepo.findAllById(productIds.stream().filter(id -> id != null).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        return buildLines(event.getTenantId(), functions, rentByFunction, itemsByFunction, products);
    }

    static List<SalesQuoteLine> buildLines(Long tenantId,
                                           List<EventFunction> functions,
                                           Map<Long, Reservation> rentByFunction,
                                           Map<Long, List<EventFunctionItem>> itemsByFunction,
                                           Map<Long, Product> products) {
        List<SalesQuoteLine> lines = new ArrayList<>();
        for (EventFunction function : functions) {
            LocalDate day = function.getStartsAt().toLocalDate();
            Reservation rent = rentByFunction.get(function.getId());
            if (rent != null && rent.getQty() != null && rent.getQty() > 0
                    && rent.getUnitPrice() != null && rent.getUnitPrice().signum() > 0) {
                Product product = products.get(rent.getProductId());
                BigDecimal rate = money(rent.getUnitPrice());
                lines.add(line(tenantId, SalesQuoteLine.Group.MEETING, product, function.getId(),
                        functionLabel(function), day, rent.getUom(), rent.getQty(), rate, rate));
            }
            for (EventFunctionItem item : itemsByFunction.getOrDefault(function.getId(), List.of())) {
                if (item.getQty() == null || item.getQty() <= 0 || item.getUnitPrice() == null) {
                    continue;
                }
                Product product = products.get(item.getProductId());
                BigDecimal base = money(item.getUnitPrice());
                BigDecimal offered = item.getGrossAmount() != null
                        ? money(item.getGrossAmount().divide(BigDecimal.valueOf(item.getQty()), 8, RoundingMode.HALF_UP))
                        : base;
                String description = StringUtils.hasText(item.getDescription()) ? item.getDescription()
                        : product != null ? product.getName() : "Item";
                lines.add(line(tenantId, groupFor(product, function), product, function.getId(),
                        description, day, item.getUom(), item.getQty(), base, offered.min(base)));
            }
        }
        return lines;
    }

    static SalesQuoteLine.Group groupFor(Product product, EventFunction function) {
        if (product != null && product.getSalesGroup() != null && product.getSalesGroup() != SalesQuoteLine.Group.DISCOUNT) {
            return product.getSalesGroup();
        }
        return CATERING.contains(function.getFunctionType()) ? SalesQuoteLine.Group.F_AND_B : SalesQuoteLine.Group.EXTRAS;
    }

    /** Line amounts, totals, discount % against base rates and the VAT contained in the offered (gross) amounts. */
    static void recalculate(SalesQuote quote, List<SalesQuoteLine> lines) {
        BigDecimal base = BigDecimal.ZERO;
        BigDecimal offered = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SalesQuoteLine line : lines) {
            line.setAmountBase(money(line.getBaseRate().multiply(BigDecimal.valueOf(line.getQty()))));
            line.setAmountOffered(money(line.getOfferedRate().multiply(BigDecimal.valueOf(line.getQty()))));
            base = base.add(line.getAmountBase());
            offered = offered.add(line.getAmountOffered());
            BigDecimal taxPercent = line.getTax1Percent().add(line.getTax2Percent());
            if (taxPercent.signum() > 0) {
                tax = tax.add(line.getAmountOffered().multiply(taxPercent)
                        .divide(HUNDRED.add(taxPercent), 8, RoundingMode.HALF_UP));
            }
        }
        if (offered.signum() < 0) {
            throw new IllegalArgumentException("Discounts exceed the quote total");
        }
        quote.setTotalBase(money(base));
        quote.setTotalOffered(money(offered));
        quote.setTotalTax(money(tax));
        quote.setDiscountPercent(base.signum() == 0 ? BigDecimal.ZERO.setScale(4)
                : base.subtract(offered).multiply(HUNDRED).divide(base, 4, RoundingMode.HALF_UP).max(BigDecimal.ZERO));
    }

    static List<SalesDtos.GroupTotal> groupTotals(List<SalesQuoteLine> lines) {
        Map<SalesQuoteLine.Group, BigDecimal[]> sums = new EnumMap<>(SalesQuoteLine.Group.class);
        for (SalesQuoteLine line : lines) {
            BigDecimal[] sum = sums.computeIfAbsent(line.getLineGroup(), g -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            sum[0] = sum[0].add(line.getAmountBase() != null ? line.getAmountBase() : BigDecimal.ZERO);
            sum[1] = sum[1].add(line.getAmountOffered() != null ? line.getAmountOffered() : BigDecimal.ZERO);
        }
        return sums.entrySet().stream()
                .map(e -> new SalesDtos.GroupTotal(e.getKey(), money(e.getValue()[0]), money(e.getValue()[1])))
                .toList();
    }

    private void applyLine(Long tenantId, SalesQuoteLine line, SalesDtos.QuoteLineRequest request, boolean creating) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        SalesQuoteLine.Group group = request.lineGroup() != null ? request.lineGroup()
                : line.getLineGroup() != null ? line.getLineGroup() : SalesQuoteLine.Group.EXTRAS;
        Product product = null;
        Long productId = request.productId() != null ? request.productId() : creating ? null : line.getProductId();
        if (productId != null) {
            product = productRepo.findByIdAndTenantId(productId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("Product not found: " + request.productId()));
        }
        String description = StringUtils.hasText(request.description()) ? request.description().trim()
                : line.getDescription() != null ? line.getDescription()
                : product != null ? product.getName() : null;
        if (description == null) {
            throw new IllegalArgumentException("description is required");
        }
        int qty = request.qty() != null ? request.qty() : line.getQty() != null ? line.getQty() : 1;
        if (qty <= 0) {
            throw new IllegalArgumentException("qty must be > 0");
        }
        BigDecimal baseRate;
        BigDecimal offeredRate;
        if (group == SalesQuoteLine.Group.DISCOUNT) {
            BigDecimal value = request.offeredRate() != null ? request.offeredRate() : line.getOfferedRate();
            if (value == null || value.signum() == 0) {
                throw new IllegalArgumentException("A discount line needs a non-zero amount");
            }
            baseRate = BigDecimal.ZERO;
            offeredRate = money(value.abs().negate());
        } else {
            baseRate = request.baseRate() != null ? request.baseRate() : line.getBaseRate();
            offeredRate = request.offeredRate() != null ? request.offeredRate() : line.getOfferedRate();
            if (baseRate == null && offeredRate != null) {
                baseRate = offeredRate;
            }
            if (offeredRate == null) {
                offeredRate = baseRate;
            }
            if (baseRate == null || baseRate.signum() < 0 || offeredRate.signum() < 0) {
                throw new IllegalArgumentException("baseRate and offeredRate must be >= 0");
            }
            baseRate = money(baseRate);
            offeredRate = money(offeredRate);
        }
        line.setLineGroup(group);
        line.setProductId(product != null ? product.getId() : null);
        line.setDescription(description);
        line.setServiceDate(request.serviceDate() != null ? request.serviceDate() : line.getServiceDate());
        line.setUom(request.uom() != null ? request.uom() : line.getUom() != null ? line.getUom()
                : product != null ? product.getDefaultUom() : null);
        line.setQty(qty);
        line.setBaseRate(baseRate);
        line.setOfferedRate(offeredRate);
        line.setTax1Percent(firstNonNull(request.tax1Percent(), line.getTax1Percent(),
                product != null ? product.getTax1Percent() : null));
        line.setTax2Percent(firstNonNull(request.tax2Percent(), line.getTax2Percent(),
                product != null ? product.getTax2Percent() : null));
        line.setAmountBase(money(baseRate.multiply(BigDecimal.valueOf(qty))));
        line.setAmountOffered(money(offeredRate.multiply(BigDecimal.valueOf(qty))));
    }

    private void touchAfterEdit(SalesQuote quote, List<SalesQuoteLine> lines) {
        recalculate(quote, lines);
        if (quote.getStatus() == SalesQuote.Status.APPROVED) {
            quote.setStatus(SalesQuote.Status.DRAFT);
        }
        quoteRepo.save(quote);
    }

    private void closePendingApproval(SalesQuote quote, String note) {
        approvalRepo.findFirstByTenantIdAndQuoteIdAndStatus(quote.getTenantId(), quote.getId(), SalesQuoteApproval.Status.PENDING)
                .ifPresent(a -> {
                    a.setStatus(SalesQuoteApproval.Status.REJECTED);
                    a.setDecidedAt(OffsetDateTime.now());
                    a.setNote(note);
                    approvalRepo.save(a);
                });
    }

    private SalesQuote requireQuoteForWrite(Long quoteId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        return requireQuote(quoteId);
    }

    private SalesQuote requireEditableQuote(Long quoteId) {
        SalesQuote quote = requireQuoteForWrite(quoteId);
        if (!quote.getStatus().isEditable()) {
            throw new IllegalStateException("Quote is " + quote.getStatus() + "; "
                    + (quote.getStatus() == SalesQuote.Status.SENT ? "revise it to make changes" : "it can no longer be changed"));
        }
        return quote;
    }

    private LocalDate defaultValidUntil(Event event) {
        LocalDate validUntil = LocalDate.now().plusDays(validityDays);
        if (event.getDecisionDate() != null && event.getDecisionDate().isBefore(validUntil)
                && !event.getDecisionDate().isBefore(LocalDate.now())) {
            validUntil = event.getDecisionDate();
        }
        return validUntil;
    }

    private JsonNode snapshot(SalesQuote quote, Event event, CrmAccount account, List<SalesQuoteLine> lines, int version) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("quoteNumber", quote.getQuoteNumber());
        root.put("version", version);
        root.put("currency", quote.getCurrency());
        root.put("validUntil", quote.getValidUntil() != null ? quote.getValidUntil().toString() : null);
        root.put("eventName", event.getName());
        root.put("dateFrom", event.getDateFrom().toString());
        root.put("dateTo", event.getDateTo().toString());
        root.put("account", account != null ? firstNonBlank(account.getLegalName(), account.getName()) : null);
        root.put("totalBase", quote.getTotalBase());
        root.put("totalOffered", quote.getTotalOffered());
        root.put("totalTax", quote.getTotalTax());
        root.put("discountPercent", quote.getDiscountPercent());
        root.put("notes", quote.getNotes());
        root.put("terms", quote.getTerms());
        ArrayNode array = root.putArray("lines");
        for (SalesQuoteLine line : lines) {
            ObjectNode node = array.addObject();
            node.put("group", line.getLineGroup().name());
            node.put("description", line.getDescription());
            node.put("serviceDate", line.getServiceDate() != null ? line.getServiceDate().toString() : null);
            node.put("uom", line.getUom());
            node.put("qty", line.getQty());
            node.put("baseRate", line.getBaseRate());
            node.put("offeredRate", line.getOfferedRate());
            node.put("taxPercent", line.getTax1Percent().add(line.getTax2Percent()));
            node.put("amountOffered", line.getAmountOffered());
        }
        return root;
    }

    private static SalesQuoteLine line(Long tenantId, SalesQuoteLine.Group group, Product product, Long functionId,
                                       String description, LocalDate day, String uom, int qty,
                                       BigDecimal base, BigDecimal offered) {
        return SalesQuoteLine.builder()
                .tenantId(tenantId)
                .lineGroup(group)
                .productId(product != null ? product.getId() : null)
                .eventFunctionId(functionId)
                .description(description)
                .serviceDate(day)
                .uom(uom)
                .qty(qty)
                .baseRate(base)
                .offeredRate(offered)
                .tax1Percent(product != null && product.getTax1Percent() != null ? product.getTax1Percent() : BigDecimal.ZERO)
                .tax2Percent(product != null && product.getTax2Percent() != null ? product.getTax2Percent() : BigDecimal.ZERO)
                .amountBase(money(base.multiply(BigDecimal.valueOf(qty))))
                .amountOffered(money(offered.multiply(BigDecimal.valueOf(qty))))
                .displayOrder(0)
                .build();
    }

    private static String functionLabel(EventFunction function) {
        return StringUtils.hasText(function.getName()) ? function.getName() : function.getFunctionType().name();
    }

    private static BigDecimal firstNonNull(BigDecimal a, BigDecimal b, BigDecimal c) {
        return a != null ? a : b != null ? b : c != null ? c : BigDecimal.ZERO;
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String percent(BigDecimal value) {
        return value == null ? "0" : value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
