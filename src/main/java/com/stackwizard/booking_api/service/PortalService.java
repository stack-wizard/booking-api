package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Booking;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.PortalAccessToken;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesContractDocument;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteVersion;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.BookingRepository;
import com.stackwizard.booking_api.repository.SalesContractDocumentRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.repository.SalesQuoteVersionRepository;
import com.stackwizard.booking_api.security.AuthUserAccessor;
import com.stackwizard.booking_api.security.PortalAccessContext;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Client-facing read model and quote decisions. Everything is scoped by {@link PortalAccessContext};
 * drafts (quotes never sent, draft invoices, draft contracts) are never shown.
 */
@Service
public class PortalService {
    private static final Set<SalesQuote.Status> CLIENT_VISIBLE = EnumSet.of(
            SalesQuote.Status.SENT, SalesQuote.Status.ACCEPTED, SalesQuote.Status.REJECTED, SalesQuote.Status.EXPIRED);
    private static final Set<SalesContract.Status> CONTRACT_VISIBLE = EnumSet.of(SalesContract.Status.SENT, SalesContract.Status.SIGNED);

    private final PortalTokenService tokenService;
    private final AuthUserAccessor authUserAccessor;
    private final BookingRepository bookingRepo;
    private final CrmContactRepository contactRepo;
    private final CrmAccountRepository accountRepo;
    private final SalesQuoteRepository quoteRepo;
    private final SalesQuoteVersionRepository versionRepo;
    private final SalesQuoteService quoteService;
    private final SalesContractRepository contractRepo;
    private final SalesContractDocumentRepository documentRepo;
    private final SalesContractService contractService;
    private final SalesDocumentService documentService;
    private final InvoiceService invoiceService;
    private final InvoicePdfService invoicePdfService;

    public PortalService(PortalTokenService tokenService,
                         AuthUserAccessor authUserAccessor,
                         BookingRepository bookingRepo,
                         CrmContactRepository contactRepo,
                         CrmAccountRepository accountRepo,
                         SalesQuoteRepository quoteRepo,
                         SalesQuoteVersionRepository versionRepo,
                         SalesQuoteService quoteService,
                         SalesContractRepository contractRepo,
                         SalesContractDocumentRepository documentRepo,
                         SalesContractService contractService,
                         SalesDocumentService documentService,
                         InvoiceService invoiceService,
                         InvoicePdfService invoicePdfService) {
        this.tokenService = tokenService;
        this.authUserAccessor = authUserAccessor;
        this.bookingRepo = bookingRepo;
        this.contactRepo = contactRepo;
        this.accountRepo = accountRepo;
        this.quoteRepo = quoteRepo;
        this.versionRepo = versionRepo;
        this.quoteService = quoteService;
        this.contractRepo = contractRepo;
        this.documentRepo = documentRepo;
        this.contractService = contractService;
        this.documentService = documentService;
        this.invoiceService = invoiceService;
        this.invoicePdfService = invoicePdfService;
    }

    /** Anonymous link resolver. */
    @Transactional
    public PortalAccessContext fromToken(String token) {
        PortalAccessToken row = tokenService.requireValid(token);
        return new PortalAccessContext(row.getTenantId(), Set.of(row.getBookingId()), row.getContactId(),
                PortalAccessContext.Mode.TOKEN, row.getToken());
    }

    /** Logged-in Platform user resolver: contacts whose platform_user_id is the JWT subject. */
    @Transactional(readOnly = true)
    public PortalAccessContext fromPlatformUser() {
        Long tenantId = TenantResolver.requireOrgTenantId();
        AppUser user = authUserAccessor.currentAppUser()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in required"));
        if (user.getPlatformUserId() == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No client contact is linked to this user");
        }
        List<CrmContact> contacts = contactRepo.findByTenantIdAndPlatformUserId(tenantId, user.getPlatformUserId());
        if (contacts.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No client contact is linked to this user");
        }
        Set<Long> contactIds = contacts.stream().map(CrmContact::getId).collect(Collectors.toSet());
        Set<Long> accountIds = contacts.stream().map(CrmContact::getAccountId).filter(id -> id != null).collect(Collectors.toSet());
        accountIds.add(-1L);
        Set<Long> bookingIds = bookingRepo.findForPortal(tenantId, contactIds, accountIds).stream()
                .map(Booking::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        return new PortalAccessContext(tenantId, bookingIds, contacts.get(0).getId(), PortalAccessContext.Mode.PLATFORM_USER, null);
    }

    @Transactional(readOnly = true)
    public List<PortalBookingSummary> bookings(PortalAccessContext ctx) {
        List<PortalBookingSummary> out = new ArrayList<>();
        for (Long id : ctx.bookingIds()) {
            bookingRepo.findByIdAndTenantId(id, ctx.tenantId()).ifPresent(e -> out.add(summary(e)));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public PortalBookingView booking(PortalAccessContext ctx, Long bookingId) {
        Booking booking = requireBooking(ctx, bookingId);
        CrmAccount account = accountRepo.findByIdAndTenantId(booking.getAccountId(), booking.getTenantId()).orElse(null);
        List<PortalQuote> quotes = new ArrayList<>();
        for (SalesQuote quote : quoteRepo.findByTenantIdAndBookingIdOrderByIdDesc(booking.getTenantId(), booking.getId())) {
            if (!CLIENT_VISIBLE.contains(quote.getStatus()) || quote.getVersion() == 0) {
                continue;
            }
            JsonNode snapshot = versionRepo.findByTenantIdAndQuoteIdAndVersion(quote.getTenantId(), quote.getId(), quote.getVersion())
                    .map(SalesQuoteVersion::getSnapshot).orElse(null);
            quotes.add(new PortalQuote(quote.getId(), quote.getQuoteNumber(), quote.getVersion(), quote.getStatus(),
                    quote.getCurrency(), quote.getValidUntil(), quote.getTotalOffered(), quote.getTotalTax(),
                    quote.getSentAt(), quote.getDecidedAt(), quote.getDecidedByName(),
                    quote.getStatus() == SalesQuote.Status.SENT
                            && (quote.getValidUntil() == null || !quote.getValidUntil().isBefore(LocalDate.now())),
                    snapshot));
        }
        PortalContract contract = contractRepo.findFirstByTenantIdAndBookingIdAndStatusNot(
                        booking.getTenantId(), booking.getId(), SalesContract.Status.CANCELLED)
                .filter(c -> CONTRACT_VISIBLE.contains(c.getStatus()))
                .map(c -> {
                    SalesDtos.ContractView view = contractService.view(c);
                    return new PortalContract(c.getId(), c.getContractNumber(), c.getStatus(), c.getCurrency(),
                            c.getTotalAmount(), c.getSignedAt(), c.getSignedByName(), view.milestones(), view.documents());
                })
                .orElse(null);
        List<PortalInvoice> invoices = visibleInvoices(booking, contract).stream()
                .map(i -> new PortalInvoice(i.getId(), i.getInvoiceNumber(), i.getInvoiceType().name(), i.getInvoiceDate(),
                        i.getCurrency(), i.getTotalGross(), i.getPaymentStatus()))
                .toList();
        return new PortalBookingView(summary(booking), account != null ? account.getName() : null, quotes, contract, invoices);
    }

    @Transactional
    public PortalBookingView decide(PortalAccessContext ctx, Long quoteId, boolean accept, SalesDtos.DecisionRequest request) {
        SalesQuote quote = quoteRepo.findByIdAndTenantId(quoteId, ctx.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote not found"));
        requireBooking(ctx, quote.getBookingId());
        String name = request != null ? request.name() : null;
        if (accept && (name == null || name.isBlank())) {
            throw new IllegalArgumentException("Your name is required to accept the quote");
        }
        quoteService.applyDecision(quote, accept, name, request != null ? request.note() : null, true);
        return booking(ctx, quote.getBookingId());
    }

    @Transactional(readOnly = true)
    public BookingDocumentService.PdfDocument quotePdf(PortalAccessContext ctx, Long quoteId) {
        SalesQuote quote = quoteRepo.findByIdAndTenantId(quoteId, ctx.tenantId())
                .filter(q -> CLIENT_VISIBLE.contains(q.getStatus()) && q.getVersion() > 0)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Quote not found"));
        requireBooking(ctx, quote.getBookingId());
        return documentService.renderQuote(quote);
    }

    @Transactional(readOnly = true)
    public BookingDocumentService.PdfDocument contractPdf(PortalAccessContext ctx, Long contractId) {
        SalesContract contract = requireVisibleContract(ctx, contractId);
        return documentService.renderContract(contractService.view(contract));
    }

    @Transactional(readOnly = true)
    public SalesContractService.StoredFile document(PortalAccessContext ctx, Long documentId) {
        SalesContractDocument document = documentRepo.findByIdAndTenantId(documentId, ctx.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        requireVisibleContract(ctx, document.getContractId());
        return contractService.read(document);
    }

    @Transactional(readOnly = true)
    public InvoicePdfService.InvoicePdfDocument invoicePdf(PortalAccessContext ctx, Long bookingId, Long invoiceId) {
        Booking booking = requireBooking(ctx, bookingId);
        PortalContract contract = booking(ctx, bookingId).contract();
        boolean visible = visibleInvoices(booking, contract).stream().anyMatch(i -> i.getId().equals(invoiceId));
        if (!visible) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Invoice not found");
        }
        return invoicePdfService.generateInvoicePdf(invoiceId);
    }

    private List<Invoice> visibleInvoices(Booking booking, PortalContract contract) {
        List<Invoice> out = new ArrayList<>(invoiceService.findAllByReference(BookingDocumentService.INVOICE_REFERENCE_TABLE, booking.getId()));
        if (contract != null) {
            for (SalesDtos.MilestoneView m : contract.milestones()) {
                if (m.milestone().getInvoiceId() != null) {
                    out.addAll(invoiceService.findAllByReference(SalesContractService.MILESTONE_REFERENCE_TABLE, m.milestone().getId()));
                }
            }
        }
        return out.stream()
                .filter(i -> booking.getTenantId().equals(i.getTenantId()) && i.getStatus() == InvoiceStatus.ISSUED)
                .collect(Collectors.toMap(Invoice::getId, i -> i, (a, b) -> a, java.util.LinkedHashMap::new))
                .values().stream().toList();
    }

    private SalesContract requireVisibleContract(PortalAccessContext ctx, Long contractId) {
        SalesContract contract = contractRepo.findByIdAndTenantId(contractId, ctx.tenantId())
                .filter(c -> CONTRACT_VISIBLE.contains(c.getStatus()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));
        requireBooking(ctx, contract.getBookingId());
        return contract;
    }

    private Booking requireBooking(PortalAccessContext ctx, Long bookingId) {
        if (!ctx.allows(bookingId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found");
        }
        return bookingRepo.findByIdAndTenantId(bookingId, ctx.tenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Booking not found"));
    }

    private static PortalBookingSummary summary(Booking e) {
        return new PortalBookingSummary(e.getId(), e.getName(), e.getStatus().name(), e.getDateFrom(), e.getDateTo(),
                e.getExpectedPax(), e.getGuaranteedPax(), e.getGuaranteeDueDate());
    }

    public record PortalBookingSummary(Long id, String name, String status, LocalDate dateFrom, LocalDate dateTo,
                                     Integer expectedPax, Integer guaranteedPax, LocalDate guaranteeDueDate) {
    }

    public record PortalQuote(Long id, String quoteNumber, Integer version, SalesQuote.Status status, String currency,
                              LocalDate validUntil, BigDecimal totalOffered, BigDecimal totalTax,
                              OffsetDateTime sentAt, OffsetDateTime decidedAt, String decidedByName,
                              boolean canDecide, JsonNode content) {
    }

    public record PortalContract(Long id, String contractNumber, SalesContract.Status status, String currency,
                                 BigDecimal totalAmount, OffsetDateTime signedAt, String signedByName,
                                 List<SalesDtos.MilestoneView> milestones, List<SalesContractDocument> documents) {
    }

    public record PortalInvoice(Long id, String invoiceNumber, String invoiceType, LocalDate invoiceDate,
                                String currency, BigDecimal totalGross, String paymentStatus) {
    }

    public record PortalBookingView(PortalBookingSummary booking, String accountName, List<PortalQuote> quotes,
                                  PortalContract contract, List<PortalInvoice> invoices) {
    }
}
