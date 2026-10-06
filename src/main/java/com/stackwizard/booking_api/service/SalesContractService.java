package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.InvoiceCreateItemRequest;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.dto.SalesDtos;
import com.stackwizard.booking_api.model.CrmAccount;
import com.stackwizard.booking_api.model.CrmContact;
import com.stackwizard.booking_api.model.Event;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.SalesContract;
import com.stackwizard.booking_api.model.SalesContractDocument;
import com.stackwizard.booking_api.model.SalesPaymentMilestone;
import com.stackwizard.booking_api.model.SalesQuote;
import com.stackwizard.booking_api.model.SalesQuoteLine;
import com.stackwizard.booking_api.repository.CrmAccountRepository;
import com.stackwizard.booking_api.repository.CrmContactRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.SalesContractDocumentRepository;
import com.stackwizard.booking_api.repository.SalesContractRepository;
import com.stackwizard.booking_api.repository.SalesPaymentMilestoneRepository;
import com.stackwizard.booking_api.repository.SalesQuoteLineRepository;
import com.stackwizard.booking_api.repository.SalesQuoteRepository;
import com.stackwizard.booking_api.security.CrmAccessContext;
import com.stackwizard.booking_api.security.CrmPermission;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Contract on the accepted quote with a payment plan. DEPOSIT and INTERIM milestones issue advance (DEPOSIT)
 * invoices; FINAL issues the full invoice from the quote lines, against which advances are settled.
 */
@Service
public class SalesContractService {
    public static final String MILESTONE_REFERENCE_TABLE = "sales_payment_milestone";
    private static final String DEPOSIT_PRODUCT_TYPE = "DEPOSIT";
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final SalesContractRepository contractRepo;
    private final SalesContractDocumentRepository documentRepo;
    private final SalesPaymentMilestoneRepository milestoneRepo;
    private final SalesQuoteRepository quoteRepo;
    private final SalesQuoteLineRepository quoteLineRepo;
    private final EventService eventService;
    private final CrmAccountRepository accountRepo;
    private final CrmContactRepository contactRepo;
    private final ProductRepository productRepo;
    private final InvoiceService invoiceService;
    private final InvoiceRepository invoiceRepo;
    private final MediaStorageService mediaStorageService;
    private final CrmAccessContext accessContext;
    private final BigDecimal depositPercent;

    public SalesContractService(SalesContractRepository contractRepo,
                                SalesContractDocumentRepository documentRepo,
                                SalesPaymentMilestoneRepository milestoneRepo,
                                SalesQuoteRepository quoteRepo,
                                SalesQuoteLineRepository quoteLineRepo,
                                EventService eventService,
                                CrmAccountRepository accountRepo,
                                CrmContactRepository contactRepo,
                                ProductRepository productRepo,
                                InvoiceService invoiceService,
                                InvoiceRepository invoiceRepo,
                                MediaStorageService mediaStorageService,
                                CrmAccessContext accessContext,
                                @Value("${crm.contracts.deposit-percent:30}") BigDecimal depositPercent) {
        this.contractRepo = contractRepo;
        this.documentRepo = documentRepo;
        this.milestoneRepo = milestoneRepo;
        this.quoteRepo = quoteRepo;
        this.quoteLineRepo = quoteLineRepo;
        this.eventService = eventService;
        this.accountRepo = accountRepo;
        this.contactRepo = contactRepo;
        this.productRepo = productRepo;
        this.invoiceService = invoiceService;
        this.invoiceRepo = invoiceRepo;
        this.mediaStorageService = mediaStorageService;
        this.accessContext = accessContext;
        this.depositPercent = depositPercent;
    }

    @Transactional(readOnly = true)
    public List<SalesDtos.ContractView> forEvent(Long eventId) {
        Event event = eventService.requireEvent(eventId);
        return contractRepo.findByTenantIdAndEventIdOrderByIdDesc(event.getTenantId(), event.getId()).stream()
                .map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public SalesDtos.ContractView get(Long contractId) {
        return view(requireContract(contractId));
    }

    SalesDtos.ContractView view(SalesContract contract) {
        List<SalesPaymentMilestone> milestones = milestoneRepo.findByTenantIdAndContractIdOrderByDisplayOrderAscIdAsc(
                contract.getTenantId(), contract.getId());
        Map<Long, Invoice> invoices = invoiceRepo.findAllById(milestones.stream()
                        .map(SalesPaymentMilestone::getInvoiceId).filter(id -> id != null).toList()).stream()
                .filter(i -> contract.getTenantId().equals(i.getTenantId()))
                .collect(Collectors.toMap(Invoice::getId, Function.identity()));
        String quoteNumber = quoteRepo.findByIdAndTenantId(contract.getQuoteId(), contract.getTenantId())
                .map(SalesQuote::getQuoteNumber).orElse(null);
        return new SalesDtos.ContractView(contract, quoteNumber,
                milestones.stream().map(m -> SalesDtos.MilestoneView.of(m, invoices.get(m.getInvoiceId()))).toList(),
                documentRepo.findByTenantIdAndContractIdOrderByCreatedAtDescIdDesc(contract.getTenantId(), contract.getId()));
    }

    /** DRAFT contract on the accepted quote with a default deposit + final payment plan. */
    @Transactional
    public SalesDtos.ContractView create(Long eventId, SalesDtos.ContractRequest request) {
        Event event = eventService.requireEditable(eventId);
        Long tenantId = event.getTenantId();
        SalesQuote quote = quoteRepo.findFirstByTenantIdAndEventIdAndStatusOrderByIdDesc(tenantId, event.getId(), SalesQuote.Status.ACCEPTED)
                .orElseThrow(() -> new IllegalStateException("The event needs an accepted quote before a contract"));
        if (contractRepo.findFirstByTenantIdAndEventIdAndStatusNot(tenantId, event.getId(), SalesContract.Status.CANCELLED).isPresent()) {
            throw new IllegalStateException("The event already has a contract");
        }
        int year = LocalDate.now().getYear();
        String prefix = "C" + year + "-";
        long seq = contractRepo.countByNumberPrefix(tenantId, prefix + "%") + 1;
        SalesContract contract = contractRepo.save(SalesContract.builder()
                .tenantId(tenantId)
                .eventId(event.getId())
                .quoteId(quote.getId())
                .contractNumber(prefix + String.format("%04d", seq))
                .status(SalesContract.Status.DRAFT)
                .currency(quote.getCurrency())
                .totalAmount(quote.getTotalOffered())
                .terms(request != null && StringUtils.hasText(request.terms()) ? request.terms().trim() : quote.getTerms())
                .build());
        milestoneRepo.saveAll(defaultMilestones(contract, event, depositPercent, LocalDate.now()));
        return view(contract);
    }

    @Transactional
    public SalesDtos.ContractView update(Long contractId, SalesDtos.ContractRequest request) {
        SalesContract contract = requireContractForWrite(contractId);
        if (contract.getStatus() == SalesContract.Status.SIGNED || contract.getStatus() == SalesContract.Status.CANCELLED) {
            throw new IllegalStateException("Contract is " + contract.getStatus() + " and can no longer be changed");
        }
        contract.setTerms(request != null && StringUtils.hasText(request.terms()) ? request.terms().trim() : null);
        return view(contractRepo.save(contract));
    }

    /** Replaces the payment plan. Amounts must add up to the contract total and exactly one milestone is FINAL. */
    @Transactional
    public SalesDtos.ContractView replaceMilestones(Long contractId, List<SalesDtos.MilestoneRequest> requests) {
        SalesContract contract = requireContractForWrite(contractId);
        if (contract.getStatus() == SalesContract.Status.CANCELLED) {
            throw new IllegalStateException("Contract is CANCELLED");
        }
        List<SalesPaymentMilestone> existing = milestoneRepo.findByTenantIdAndContractIdOrderByDisplayOrderAscIdAsc(
                contract.getTenantId(), contract.getId());
        if (existing.stream().anyMatch(m -> m.getStatus() == SalesPaymentMilestone.Status.INVOICED)) {
            throw new IllegalStateException("The payment plan cannot change after a milestone was invoiced");
        }
        List<SalesPaymentMilestone> rows = normalizeMilestones(contract, requests);
        milestoneRepo.deleteAll(existing);
        milestoneRepo.flush();
        milestoneRepo.saveAll(rows);
        return view(contract);
    }

    @Transactional
    public SalesDtos.ContractView send(Long contractId) {
        SalesContract contract = requireContractForWrite(contractId);
        if (contract.getStatus() != SalesContract.Status.DRAFT && contract.getStatus() != SalesContract.Status.SENT) {
            throw new IllegalStateException("Contract cannot be sent from " + contract.getStatus());
        }
        contract.setStatus(SalesContract.Status.SENT);
        contract.setSentAt(OffsetDateTime.now());
        return view(contractRepo.save(contract));
    }

    @Transactional
    public SalesDtos.ContractView sign(Long contractId, SalesDtos.SignRequest request) {
        SalesContract contract = requireContractForWrite(contractId);
        if (contract.getStatus() != SalesContract.Status.DRAFT && contract.getStatus() != SalesContract.Status.SENT) {
            throw new IllegalStateException("Contract cannot be signed from " + contract.getStatus());
        }
        if (request == null || !StringUtils.hasText(request.signedByName())) {
            throw new IllegalArgumentException("signedByName is required");
        }
        contract.setStatus(SalesContract.Status.SIGNED);
        contract.setSignedByName(request.signedByName().trim());
        contract.setSignedAt(request.signedAt() != null ? request.signedAt() : OffsetDateTime.now());
        return view(contractRepo.save(contract));
    }

    @Transactional
    public SalesDtos.ContractView cancel(Long contractId) {
        SalesContract contract = requireContractForWrite(contractId);
        accessContext.require(CrmPermission.OPPORTUNITY_CLOSE);
        if (contract.getStatus() == SalesContract.Status.CANCELLED) {
            throw new IllegalStateException("Contract is already CANCELLED");
        }
        List<SalesPaymentMilestone> milestones = milestoneRepo.findByTenantIdAndContractIdOrderByDisplayOrderAscIdAsc(
                contract.getTenantId(), contract.getId());
        milestones.stream().filter(m -> m.getStatus() == SalesPaymentMilestone.Status.PLANNED)
                .forEach(m -> m.setStatus(SalesPaymentMilestone.Status.CANCELLED));
        milestoneRepo.saveAll(milestones);
        contract.setStatus(SalesContract.Status.CANCELLED);
        contract.setCancelledAt(OffsetDateTime.now());
        return view(contractRepo.save(contract));
    }

    @Transactional
    public SalesContractDocument uploadDocument(Long contractId, SalesContractDocument.Kind kind, MultipartFile file) {
        SalesContract contract = requireContractForWrite(contractId);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("file is required");
        }
        String key = mediaStorageService.upload("sales-contracts", contract.getTenantId(), "contract-" + contract.getId(), file);
        return documentRepo.save(SalesContractDocument.builder()
                .tenantId(contract.getTenantId())
                .contractId(contract.getId())
                .kind(kind != null ? kind : SalesContractDocument.Kind.OTHER)
                .fileName(StringUtils.hasText(file.getOriginalFilename()) ? file.getOriginalFilename() : "document")
                .contentType(file.getContentType())
                .sizeBytes(file.getSize())
                .storageKey(key)
                .createdBy(accessContext.currentUserId())
                .build());
    }

    @Transactional(readOnly = true)
    public StoredFile downloadDocument(Long documentId) {
        accessContext.require(CrmPermission.EVENT_READ);
        SalesContractDocument document = documentRepo.findByIdAndTenantId(documentId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        requireContract(document.getContractId());
        return read(document);
    }

    StoredFile read(SalesContractDocument document) {
        return new StoredFile(document.getFileName(), document.getContentType(), mediaStorageService.download(document.getStorageKey()));
    }

    @Transactional
    public void deleteDocument(Long documentId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        SalesContractDocument document = documentRepo.findByIdAndTenantId(documentId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found"));
        requireContract(document.getContractId());
        documentRepo.delete(document);
    }

    /** Draft invoice for the milestone; the contract must be SENT or SIGNED. */
    @Transactional
    public SalesDtos.ContractView invoiceMilestone(Long milestoneId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        SalesPaymentMilestone milestone = milestoneRepo.findByIdAndTenantId(milestoneId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Milestone not found"));
        SalesContract contract = requireContract(milestone.getContractId());
        if (contract.getStatus() != SalesContract.Status.SENT && contract.getStatus() != SalesContract.Status.SIGNED) {
            throw new IllegalStateException("Send or sign the contract before invoicing; it is " + contract.getStatus());
        }
        if (milestone.getStatus() != SalesPaymentMilestone.Status.PLANNED) {
            throw new IllegalStateException("Milestone is already " + milestone.getStatus());
        }
        Event event = eventService.requireEvent(contract.getEventId());
        InvoiceCreateRequest request = customer(contract, event);
        if (milestone.getKind() == SalesPaymentMilestone.Kind.FINAL) {
            List<SalesQuoteLine> lines = quoteLineRepo.findByTenantIdAndQuoteIdOrderByDisplayOrderAscIdAsc(
                    contract.getTenantId(), contract.getQuoteId());
            request.setInvoiceType(InvoiceType.INVOICE);
            request.setItems(finalInvoiceItems(lines));
        } else {
            Product deposit = productRepo.findFirstByTenantIdAndProductTypeIgnoreCaseOrderByDisplayOrderAscIdAsc(
                            contract.getTenantId(), DEPOSIT_PRODUCT_TYPE)
                    .orElseThrow(() -> new IllegalStateException("Create a product of type DEPOSIT to invoice advances"));
            InvoiceCreateItemRequest item = new InvoiceCreateItemRequest();
            item.setProductId(deposit.getId());
            item.setDescription(milestoneLabel(milestone) + " - " + contract.getContractNumber() + " " + event.getName());
            item.setQuantity(1);
            item.setUnitPriceGross(milestone.getAmount());
            request.setInvoiceType(InvoiceType.DEPOSIT);
            request.setItems(List.of(item));
        }
        Invoice invoice = invoiceService.createManualDraft(request);
        invoice.setReferenceTable(MILESTONE_REFERENCE_TABLE);
        invoice.setReferenceId(milestone.getId());
        invoice = invoiceRepo.save(invoice);
        milestone.setStatus(SalesPaymentMilestone.Status.INVOICED);
        milestone.setInvoiceId(invoice.getId());
        milestoneRepo.save(milestone);
        return view(contract);
    }

    static List<SalesPaymentMilestone> defaultMilestones(SalesContract contract, Event event, BigDecimal depositPercent, LocalDate today) {
        BigDecimal total = contract.getTotalAmount();
        BigDecimal deposit = money(total.multiply(depositPercent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
        LocalDate depositDue = today.plusDays(7);
        LocalDate beforeEvent = event.getDateFrom().minusDays(1);
        if (beforeEvent.isBefore(depositDue)) {
            depositDue = beforeEvent.isBefore(today) ? today : beforeEvent;
        }
        LocalDate finalDue = event.getDateTo().isBefore(today) ? today : event.getDateTo();
        List<SalesPaymentMilestone> rows = new ArrayList<>();
        if (deposit.signum() > 0 && depositPercent.compareTo(HUNDRED) < 0) {
            rows.add(milestone(contract, SalesPaymentMilestone.Kind.DEPOSIT, "Deposit", depositDue, depositPercent, deposit, 0));
        }
        BigDecimal rest = money(total.subtract(rows.isEmpty() ? BigDecimal.ZERO : deposit));
        rows.add(milestone(contract, SalesPaymentMilestone.Kind.FINAL, "Final invoice", finalDue,
                rows.isEmpty() ? HUNDRED : HUNDRED.subtract(depositPercent), rest, rows.size()));
        return rows;
    }

    static List<SalesPaymentMilestone> normalizeMilestones(SalesContract contract, List<SalesDtos.MilestoneRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            throw new IllegalArgumentException("At least one milestone is required");
        }
        long finals = requests.stream().filter(r -> r != null && r.kind() == SalesPaymentMilestone.Kind.FINAL).count();
        if (finals != 1) {
            throw new IllegalArgumentException("Exactly one FINAL milestone is required");
        }
        BigDecimal total = contract.getTotalAmount();
        List<SalesPaymentMilestone> rows = new ArrayList<>();
        BigDecimal sum = BigDecimal.ZERO;
        SalesPaymentMilestone finalRow = null;
        int order = 0;
        for (SalesDtos.MilestoneRequest r : requests) {
            if (r == null || r.kind() == null) {
                throw new IllegalArgumentException("kind is required");
            }
            if (r.dueDate() == null) {
                throw new IllegalArgumentException("dueDate is required");
            }
            if (r.percent() != null && (r.percent().signum() <= 0 || r.percent().compareTo(HUNDRED) > 0)) {
                throw new IllegalArgumentException("percent must be between 0 and 100");
            }
            BigDecimal amount = r.amount() != null ? money(r.amount())
                    : r.percent() != null ? money(total.multiply(r.percent()).divide(HUNDRED, 8, RoundingMode.HALF_UP))
                    : null;
            if (amount != null && amount.signum() < 0) {
                throw new IllegalArgumentException("amount must be >= 0");
            }
            SalesPaymentMilestone row = milestone(contract, r.kind(), StringUtils.hasText(r.label()) ? r.label().trim() : null,
                    r.dueDate(), r.percent(), amount, order++);
            if (r.kind() == SalesPaymentMilestone.Kind.FINAL) {
                finalRow = row;
            } else {
                if (amount == null) {
                    throw new IllegalArgumentException("amount or percent is required for " + r.kind());
                }
                sum = sum.add(amount);
            }
            rows.add(row);
        }
        if (finalRow.getAmount() == null) {
            finalRow.setAmount(money(total.subtract(sum)));
        }
        if (finalRow.getAmount().signum() < 0) {
            throw new IllegalArgumentException("Advance milestones exceed the contract total " + total);
        }
        BigDecimal planned = sum.add(finalRow.getAmount());
        if (planned.subtract(total).abs().compareTo(TOLERANCE) > 0) {
            throw new IllegalArgumentException("Milestones add up to " + money(planned) + ", contract total is " + money(total));
        }
        rows.sort((a, b) -> a.getKind() == SalesPaymentMilestone.Kind.FINAL ? 1
                : b.getKind() == SalesPaymentMilestone.Kind.FINAL ? -1 : a.getDueDate().compareTo(b.getDueDate()));
        for (int i = 0; i < rows.size(); i++) {
            rows.get(i).setDisplayOrder(i);
        }
        return rows;
    }

    /** Positive quote lines at the offered rate; DISCOUNT lines become one uniform discount percent. */
    static List<InvoiceCreateItemRequest> finalInvoiceItems(List<SalesQuoteLine> lines) {
        BigDecimal positive = BigDecimal.ZERO;
        BigDecimal discount = BigDecimal.ZERO;
        for (SalesQuoteLine line : lines) {
            if (line.getLineGroup() == SalesQuoteLine.Group.DISCOUNT) {
                discount = discount.add(line.getAmountOffered().abs());
            } else {
                positive = positive.add(line.getAmountOffered());
            }
        }
        BigDecimal discountPercent = positive.signum() == 0 ? BigDecimal.ZERO
                : discount.multiply(HUNDRED).divide(positive, 4, RoundingMode.HALF_UP);
        List<InvoiceCreateItemRequest> items = new ArrayList<>();
        for (SalesQuoteLine line : lines) {
            if (line.getLineGroup() == SalesQuoteLine.Group.DISCOUNT) {
                continue;
            }
            InvoiceCreateItemRequest item = new InvoiceCreateItemRequest();
            item.setProductId(line.getProductId());
            item.setDescription(line.getDescription() + (line.getServiceDate() != null ? " " + line.getServiceDate() : ""));
            item.setQuantity(line.getQty());
            item.setUom(line.getUom());
            item.setUnitPriceGross(line.getOfferedRate());
            item.setTax1Percent(line.getTax1Percent());
            item.setTax2Percent(line.getTax2Percent());
            item.setDiscountPercent(discountPercent.signum() > 0 ? discountPercent : null);
            items.add(item);
        }
        if (items.isEmpty()) {
            throw new IllegalStateException("The quote has nothing to invoice");
        }
        return items;
    }

    private InvoiceCreateRequest customer(SalesContract contract, Event event) {
        CrmAccount account = accountRepo.findByIdAndTenantId(event.getAccountId(), event.getTenantId()).orElse(null);
        CrmContact contact = event.getPrimaryContactId() == null ? null
                : contactRepo.findByIdAndTenantId(event.getPrimaryContactId(), event.getTenantId()).orElse(null);
        InvoiceCreateRequest request = new InvoiceCreateRequest();
        request.setTenantId(contract.getTenantId());
        request.setCurrency(contract.getCurrency());
        request.setCustomerName(account != null ? firstNonBlank(account.getLegalName(), account.getName()) : event.getName());
        request.setCustomerEmail(contact != null && contact.getEmail() != null ? contact.getEmail() : account != null ? account.getEmail() : null);
        request.setCustomerPhone(contact != null && contact.getPhone() != null ? contact.getPhone() : account != null ? account.getPhone() : null);
        return request;
    }

    SalesContract requireContract(Long contractId) {
        accessContext.require(CrmPermission.EVENT_READ);
        SalesContract contract = contractRepo.findByIdAndTenantId(contractId, TenantResolver.requireTenantId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contract not found"));
        eventService.requireEvent(contract.getEventId());
        return contract;
    }

    private SalesContract requireContractForWrite(Long contractId) {
        accessContext.require(CrmPermission.EVENT_WRITE);
        return requireContract(contractId);
    }

    private static SalesPaymentMilestone milestone(SalesContract contract, SalesPaymentMilestone.Kind kind, String label,
                                                   LocalDate due, BigDecimal percent, BigDecimal amount, int order) {
        return SalesPaymentMilestone.builder()
                .tenantId(contract.getTenantId())
                .contractId(contract.getId())
                .kind(kind)
                .label(label)
                .dueDate(due)
                .percent(percent)
                .amount(amount)
                .status(SalesPaymentMilestone.Status.PLANNED)
                .displayOrder(order)
                .build();
    }

    static String milestoneLabel(SalesPaymentMilestone milestone) {
        if (StringUtils.hasText(milestone.getLabel())) {
            return milestone.getLabel();
        }
        return switch (milestone.getKind()) {
            case DEPOSIT -> "Deposit";
            case INTERIM -> "Interim payment";
            case FINAL -> "Final invoice";
        };
    }

    private static String firstNonBlank(String a, String b) {
        return StringUtils.hasText(a) ? a : b;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    public record StoredFile(String fileName, String contentType, byte[] content) {
    }
}
