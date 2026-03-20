package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.InvoiceCreateItemRequest;
import com.stackwizard.booking_api.dto.InvoiceCreateRequest;
import com.stackwizard.booking_api.dto.InvoiceIssueRequest;
import com.stackwizard.booking_api.dto.InvoiceSearchCriteria;
import com.stackwizard.booking_api.model.AppUser;
import com.stackwizard.booking_api.model.FiscalBusinessPremise;
import com.stackwizard.booking_api.model.FiscalCashRegister;
import com.stackwizard.booking_api.model.InvoiceFiscalizationStatus;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceSequence;
import com.stackwizard.booking_api.model.InvoiceStatus;
import com.stackwizard.booking_api.model.InvoiceType;
import com.stackwizard.booking_api.model.OperaPostingStatus;
import com.stackwizard.booking_api.model.IssuedByMode;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.PriceListEntry;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.InvoiceSequenceRepository;
import com.stackwizard.booking_api.repository.PriceListEntryRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.AppUserRepository;
import com.stackwizard.booking_api.repository.specification.InvoiceSpecifications;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InvoiceService {
    private static final String ONLINE_SYSTEM_USERNAME_PREFIX = "online-system-tenant-";
    private static final String PRODUCT_TYPE_DEPOSIT = "DEPOSIT";
    private static final String REFERENCE_TABLE_RESERVATION_REQUEST = "reservation_request";
    private static final String REFERENCE_TABLE_PAYMENT_INTENT = "payment_intent";
    private static final String REFERENCE_TABLE_INVOICE = "invoice";
    private static final String DEFAULT_CURRENCY = "EUR";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final InvoiceSequenceRepository sequenceRepo;
    private final PaymentTransactionService paymentTransactionService;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final ProductRepository productRepo;
    private final PriceListEntryRepository priceListRepo;
    private final FiscalBusinessPremiseService fiscalBusinessPremiseService;
    private final FiscalCashRegisterService fiscalCashRegisterService;
    private final AppUserRepository appUserRepo;

    public InvoiceService(InvoiceRepository invoiceRepo,
                          InvoiceItemRepository invoiceItemRepo,
                          InvoicePaymentAllocationRepository allocationRepo,
                          InvoiceSequenceRepository sequenceRepo,
                          PaymentTransactionService paymentTransactionService,
                          ReservationRequestRepository requestRepo,
                          ReservationRepository reservationRepo,
                          ProductRepository productRepo,
                          PriceListEntryRepository priceListRepo,
                          FiscalBusinessPremiseService fiscalBusinessPremiseService,
                          FiscalCashRegisterService fiscalCashRegisterService,
                          AppUserRepository appUserRepo) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.sequenceRepo = sequenceRepo;
        this.paymentTransactionService = paymentTransactionService;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.productRepo = productRepo;
        this.priceListRepo = priceListRepo;
        this.fiscalBusinessPremiseService = fiscalBusinessPremiseService;
        this.fiscalCashRegisterService = fiscalCashRegisterService;
        this.appUserRepo = appUserRepo;
    }

    public Optional<Invoice> findByReference(String referenceTable, Long referenceId) {
        return invoiceRepo.findByReferenceTableAndReferenceId(referenceTable, referenceId);
    }

    @Transactional(readOnly = true)
    public Page<Invoice> search(InvoiceSearchCriteria criteria, Pageable pageable) {
        InvoiceSearchCriteria normalized = normalizeAndValidate(criteria);
        return invoiceRepo.findAll(InvoiceSpecifications.byCriteria(normalized), pageable);
    }

    public Optional<Invoice> findById(Long id) {
        return invoiceRepo.findById(id);
    }

    public Optional<Invoice> findPreferredByRequestId(Long requestId) {
        List<Invoice> invoices = findByRequestId(requestId);
        for (Invoice invoice : invoices) {
            if (invoice.getInvoiceType() == InvoiceType.DEPOSIT) {
                return Optional.of(invoice);
            }
        }
        return invoices.isEmpty() ? Optional.empty() : Optional.of(invoices.get(0));
    }

    public List<Invoice> findByRequestId(Long requestId) {
        return invoiceRepo.findByReservationRequestIdOrderByCreatedAtDescIdDesc(requestId);
    }

    public List<InvoiceItem> findItems(Long invoiceId) {
        return invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(invoiceId);
    }

    public List<InvoicePaymentAllocation> findAllocations(Long invoiceId) {
        return allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(invoiceId);
    }

    @Transactional
    public Invoice issueInvoice(Long invoiceId, InvoiceIssueRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));

        if (invoice.getStatus() != InvoiceStatus.DRAFT && invoice.getStatus() != InvoiceStatus.ISSUED) {
            throw new IllegalStateException("Only DRAFT or ISSUED invoices can be issued");
        }
        if (request.getBusinessPremiseId() == null) {
            throw new IllegalArgumentException("businessPremiseId is required");
        }
        if (request.getCashRegisterId() == null) {
            throw new IllegalArgumentException("cashRegisterId is required");
        }

        Long tenantId = invoice.getTenantId();
        FiscalBusinessPremise businessPremise = fiscalBusinessPremiseService
                .requireByIdAndTenantId(request.getBusinessPremiseId(), tenantId);
        FiscalCashRegister cashRegister = fiscalCashRegisterService
                .requireByIdAndTenantId(request.getCashRegisterId(), tenantId);

        if (!businessPremise.getId().equals(cashRegister.getBusinessPremiseId())) {
            throw new IllegalArgumentException("cashRegisterId does not belong to businessPremiseId");
        }
        if (!Boolean.TRUE.equals(businessPremise.getActive())) {
            throw new IllegalStateException("Fiscal business premise is inactive");
        }
        if (!Boolean.TRUE.equals(cashRegister.getActive())) {
            throw new IllegalStateException("Fiscal cash register is inactive");
        }

        IssuedByMode issuedByMode = normalizeIssuedByMode(request.getIssuedByMode());
        Long issuedByUserId = resolveIssuedByUserId(tenantId, issuedByMode, request.getIssuedByUserId());

        invoice.setIssuedByMode(issuedByMode);
        invoice.setIssuedByUserId(issuedByUserId);
        invoice.setBusinessPremiseId(businessPremise.getId());
        invoice.setCashRegisterId(cashRegister.getId());
        invoice.setBusinessPremiseCodeSnapshot(businessPremise.getCode());
        invoice.setCashRegisterCodeSnapshot(cashRegister.getCode());

        if (invoice.getStatus() != InvoiceStatus.ISSUED) {
            invoice.setStatus(InvoiceStatus.ISSUED);
        }
        if (invoice.getIssuedAt() == null) {
            invoice.setIssuedAt(OffsetDateTime.now());
        }
        if (invoice.getFiscalizationStatus() != InvoiceFiscalizationStatus.FISCALIZED) {
            if (invoice.getInvoiceType() == InvoiceType.ROOM_CHARGE) {
                invoice.setFiscalizationStatus(InvoiceFiscalizationStatus.NOT_REQUIRED);
            } else {
                invoice.setFiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED);
            }
        }

        return invoiceRepo.save(invoice);
    }

    @Transactional
    public Invoice createDraftForFinalizedRequest(Long requestId) {
        Optional<Invoice> existing = invoiceRepo.findByReferenceTableAndReferenceId(
                REFERENCE_TABLE_RESERVATION_REQUEST, requestId);
        if (existing.isPresent()) {
            return existing.get();
        }

        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found"));
        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        if (reservations.isEmpty()) {
            throw new IllegalStateException("Cannot create invoice without reservations");
        }

        Map<Long, Product> productById = new HashMap<>();
        List<Long> productIds = reservations.stream()
                .map(Reservation::getProductId)
                .filter(id -> id != null)
                .distinct()
                .toList();
        if (!productIds.isEmpty()) {
            for (Product product : productRepo.findAllById(productIds)) {
                productById.put(product.getId(), product);
            }
        }

        int year = LocalDate.now().getYear();
        int seq = nextSequence(request.getTenantId(), InvoiceType.INVOICE, year);
        String invoiceNumber = buildNumber(InvoiceType.INVOICE, year, seq);
        String currency = reservations.stream()
                .map(Reservation::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("EUR");

        Invoice invoice = Invoice.builder()
                .tenantId(request.getTenantId())
                .invoiceType(InvoiceType.INVOICE)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(LocalDate.now())
                .customerName(firstNonBlank(request.getCustomerName(),
                        reservations.stream().map(Reservation::getCustomerName).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerEmail(firstNonBlank(request.getCustomerEmail(),
                        reservations.stream().map(Reservation::getCustomerEmail).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerPhone(firstNonBlank(request.getCustomerPhone(),
                        reservations.stream().map(Reservation::getCustomerPhone).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .issuedByMode(IssuedByMode.ONLINE_SYSTEM)
                .status(InvoiceStatus.DRAFT)
                .paymentStatus("UNPAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.NOT_REQUIRED)
                .referenceTable(REFERENCE_TABLE_RESERVATION_REQUEST)
                .referenceId(requestId)
                .reservationRequestId(requestId)
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .currency(currency)
                .subtotalNet(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .discountTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax1Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax2Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .totalGross(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        invoice = invoiceRepo.save(invoice);

        BigDecimal subtotalNet = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal tax1Total = BigDecimal.ZERO;
        BigDecimal tax2Total = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;

        int lineNo = 1;
        for (Reservation reservation : reservations) {
            Product product = reservation.getProductId() != null ? productById.get(reservation.getProductId()) : null;
            BigDecimal grossAmount = amount(
                    reservation.getGrossAmount() != null
                            ? reservation.getGrossAmount()
                            : unitPrice(reservation).multiply(BigDecimal.valueOf(quantity(reservation))));
            BigDecimal unitPriceGross = amount(unitPrice(reservation));
            BigDecimal tax1Percent = percent(product != null ? product.getTax1Percent() : null);
            BigDecimal tax2Percent = percent(product != null ? product.getTax2Percent() : null);

            BigDecimal totalTaxPercent = tax1Percent.add(tax2Percent);
            BigDecimal divisor = BigDecimal.ONE.add(totalTaxPercent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal net = amount(grossAmount.divide(divisor, 8, RoundingMode.HALF_UP));
            BigDecimal tax1Amount = amount(net.multiply(tax1Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal tax2Amount = amount(net.multiply(tax2Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .lineNo(lineNo++)
                    .reservationId(reservation.getId())
                    .productId(reservation.getProductId())
                    .productName(product != null ? product.getName() : "Product " + reservation.getProductId())
                    .quantity(quantity(reservation))
                    .unitPriceGross(unitPriceGross)
                    .discountPercent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                    .discountAmount(amountZero())
                    .priceWithoutTax(net)
                    .tax1Percent(tax1Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax2Percent(tax2Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax1Amount(tax1Amount)
                    .tax2Amount(tax2Amount)
                    .nettPrice(net)
                    .grossAmount(grossAmount)
                    .build();
            invoiceItemRepo.save(item);

            subtotalNet = subtotalNet.add(net);
            tax1Total = tax1Total.add(tax1Amount);
            tax2Total = tax2Total.add(tax2Amount);
            totalGross = totalGross.add(grossAmount);
        }

        invoice.setSubtotalNet(money(subtotalNet));
        invoice.setDiscountTotal(money(discountTotal));
        invoice.setTax1Total(money(tax1Total));
        invoice.setTax2Total(money(tax2Total));
        invoice.setTotalGross(money(totalGross));
        return invoiceRepo.save(invoice);
    }

    @Transactional
    public Invoice createManualDraft(InvoiceCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Long tenantId = TenantResolver.requireTenantId(request.getTenantId());
        List<InvoiceCreateItemRequest> requestedItems = request.getItems();
        if (requestedItems == null || requestedItems.isEmpty()) {
            throw new IllegalArgumentException("items are required for manual invoice creation");
        }

        InvoiceType invoiceType = normalizeInvoiceType(request.getInvoiceType());
        if (isStornoType(invoiceType)) {
            throw new IllegalArgumentException("storno invoice types cannot be created manually");
        }
        LocalDate invoiceDate = request.getInvoiceDate() != null ? request.getInvoiceDate() : LocalDate.now();
        int year = invoiceDate.getYear();
        int seq = nextSequence(tenantId, invoiceType, year);
        String invoiceNumber = buildNumber(invoiceType, year, seq);
        String currency = normalizeCurrency(request.getCurrency());

        IssuedByMode requestedIssuedByMode = request.getIssuedByMode() != null
                ? request.getIssuedByMode()
                : IssuedByMode.ONLINE_SYSTEM;
        IssuedByMode issuedByMode = normalizeIssuedByMode(requestedIssuedByMode);
        Long issuedByUserId = resolveIssuedByUserId(tenantId, issuedByMode, request.getIssuedByUserId());

        Invoice invoice = Invoice.builder()
                .tenantId(tenantId)
                .invoiceType(invoiceType)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(invoiceDate)
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .customerPhone(request.getCustomerPhone())
                .issuedByMode(issuedByMode)
                .issuedByUserId(issuedByUserId)
                .status(InvoiceStatus.DRAFT)
                .paymentStatus("UNPAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.NOT_REQUIRED)
                .referenceTable(null)
                .referenceId(null)
                .reservationRequestId(null)
                .operaReservationId(normalizeOperaReservationId(request.getOperaReservationId()))
                .operaHotelCode(normalizeOperaHotelCode(request.getOperaHotelCode()))
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .currency(currency)
                .subtotalNet(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .discountTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax1Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax2Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .totalGross(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .build();
        invoice = invoiceRepo.save(invoice);

        BigDecimal subtotalNet = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal tax1Total = BigDecimal.ZERO;
        BigDecimal tax2Total = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;

        int lineNo = 1;
        for (InvoiceCreateItemRequest itemRequest : requestedItems) {
            if (itemRequest == null) {
                throw new IllegalArgumentException("items must not contain null elements");
            }

            Product product = resolveProductForItem(tenantId, itemRequest.getProductId());
            int quantity = normalizeQuantity(itemRequest.getQuantity());
            String productName = resolveItemDescription(itemRequest, product);
            BigDecimal tax1Percent = normalizePercent(firstNonNull(itemRequest.getTax1Percent(), product != null ? product.getTax1Percent() : null));
            BigDecimal tax2Percent = normalizePercent(firstNonNull(itemRequest.getTax2Percent(), product != null ? product.getTax2Percent() : null));
            BigDecimal unitPriceGross = resolveUnitPriceGross(itemRequest, product, tenantId, currency, invoiceDate, quantity);
            BigDecimal discountPercent = normalizeDiscountPercent(itemRequest.getDiscountPercent());

            BigDecimal grossBeforeDiscount = amount(unitPriceGross.multiply(BigDecimal.valueOf(quantity)));
            BigDecimal discountAmount = amount(grossBeforeDiscount.multiply(discountPercent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal grossAmount = amount(grossBeforeDiscount.subtract(discountAmount));
            if (grossAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("item gross amount after discount cannot be negative");
            }

            BigDecimal totalTaxPercent = tax1Percent.add(tax2Percent);
            BigDecimal divisor = BigDecimal.ONE.add(totalTaxPercent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal net = amount(grossAmount.divide(divisor, 8, RoundingMode.HALF_UP));
            BigDecimal tax1Amount = amount(net.multiply(tax1Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal tax2Amount = amount(net.multiply(tax2Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .lineNo(lineNo++)
                    .reservationId(null)
                    .productId(product != null ? product.getId() : itemRequest.getProductId())
                    .productName(productName)
                    .quantity(quantity)
                    .unitPriceGross(unitPriceGross)
                    .discountPercent(discountPercent.setScale(4, RoundingMode.HALF_UP))
                    .discountAmount(discountAmount)
                    .priceWithoutTax(net)
                    .tax1Percent(tax1Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax2Percent(tax2Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax1Amount(tax1Amount)
                    .tax2Amount(tax2Amount)
                    .nettPrice(net)
                    .grossAmount(grossAmount)
                    .build();
            invoiceItemRepo.save(item);

            subtotalNet = subtotalNet.add(net);
            discountTotal = discountTotal.add(discountAmount);
            tax1Total = tax1Total.add(tax1Amount);
            tax2Total = tax2Total.add(tax2Amount);
            totalGross = totalGross.add(grossAmount);
        }

        invoice.setSubtotalNet(money(subtotalNet));
        invoice.setDiscountTotal(money(discountTotal));
        invoice.setTax1Total(money(tax1Total));
        invoice.setTax2Total(money(tax2Total));
        invoice.setTotalGross(money(totalGross));
        return invoiceRepo.save(invoice);
    }

    @Transactional
    public Invoice updateDraft(Long invoiceId, InvoiceCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        if (invoice.getStatus() != InvoiceStatus.DRAFT) {
            throw new IllegalStateException("Only DRAFT invoices can be updated");
        }

        Long tenantId = invoice.getTenantId();

        if (request.getInvoiceDate() != null) {
            invoice.setInvoiceDate(request.getInvoiceDate());
        }

        InvoiceType targetType = request.getInvoiceType() != null ? request.getInvoiceType() : invoice.getInvoiceType();
        if (targetType == null) {
            targetType = InvoiceType.INVOICE;
        }
        if (targetType.isStornoType()) {
            throw new IllegalArgumentException("storno invoice types cannot be used for draft update");
        }
        if (targetType != invoice.getInvoiceType()) {
            int year = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate().getYear() : LocalDate.now().getYear();
            int seq = nextSequence(tenantId, targetType, year);
            invoice.setInvoiceType(targetType);
            invoice.setInvoiceNumber(buildNumber(targetType, year, seq));
        }

        if (request.getCustomerName() != null) {
            invoice.setCustomerName(request.getCustomerName());
        }
        if (request.getCustomerEmail() != null) {
            invoice.setCustomerEmail(request.getCustomerEmail());
        }
        if (request.getCustomerPhone() != null) {
            invoice.setCustomerPhone(request.getCustomerPhone());
        }
        if (request.getCurrency() != null) {
            invoice.setCurrency(normalizeCurrency(request.getCurrency()));
        }
        if (request.getOperaReservationId() != null) {
            invoice.setOperaReservationId(normalizeOperaReservationId(request.getOperaReservationId()));
        }
        if (request.getOperaHotelCode() != null) {
            invoice.setOperaHotelCode(normalizeOperaHotelCode(request.getOperaHotelCode()));
        }

        if (request.getIssuedByMode() != null || request.getIssuedByUserId() != null) {
            IssuedByMode targetMode = request.getIssuedByMode() != null
                    ? request.getIssuedByMode()
                    : invoice.getIssuedByMode();
            targetMode = normalizeIssuedByMode(targetMode);
            Long targetUserId = resolveIssuedByUserId(
                    tenantId,
                    targetMode,
                    request.getIssuedByUserId() != null ? request.getIssuedByUserId() : invoice.getIssuedByUserId()
            );
            invoice.setIssuedByMode(targetMode);
            invoice.setIssuedByUserId(targetUserId);
        }

        if (request.getItems() != null) {
            replaceItemsAndRecalculateTotals(invoice, request.getItems());
            refreshInvoicePaymentStatus(invoice);
        }

        return invoiceRepo.save(invoice);
    }

    @Transactional
    public Invoice createDepositInvoiceForPaymentIntent(PaymentIntent paymentIntent) {
        if (paymentIntent == null || paymentIntent.getId() == null) {
            throw new IllegalArgumentException("paymentIntent is required");
        }
        if (!"PAID".equalsIgnoreCase(paymentIntent.getStatus())) {
            throw new IllegalStateException("Deposit invoice can be created only for PAID payment intents");
        }

        Optional<Invoice> existing = invoiceRepo.findByReferenceTableAndReferenceId(
                REFERENCE_TABLE_PAYMENT_INTENT, paymentIntent.getId());
        if (existing.isPresent()) {
            return existing.get();
        }

        Long requestId = paymentIntent.getReservationRequestId();
        ReservationRequest request = requestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Request not found for payment intent"));
        List<Reservation> reservations = reservationRepo.findByRequestId(requestId);
        Product depositProduct = productRepo.findFirstByTenantIdAndProductTypeIgnoreCaseOrderByDisplayOrderAscIdAsc(
                        paymentIntent.getTenantId(), PRODUCT_TYPE_DEPOSIT)
                .orElseThrow(() -> new IllegalStateException("Deposit product is missing for tenant " + paymentIntent.getTenantId()));

        int year = LocalDate.now().getYear();
        int seq = nextSequence(paymentIntent.getTenantId(), InvoiceType.DEPOSIT, year);
        String invoiceNumber = buildNumber(InvoiceType.DEPOSIT, year, seq);
        BigDecimal grossAmount = money(paymentIntent.getAmount());
        BigDecimal tax1Percent = percent(depositProduct.getTax1Percent());
        BigDecimal tax2Percent = percent(depositProduct.getTax2Percent());
        BigDecimal totalTaxPercent = tax1Percent.add(tax2Percent);
        BigDecimal divisor = BigDecimal.ONE.add(totalTaxPercent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal netAmount = amount(grossAmount.divide(divisor, 8, RoundingMode.HALF_UP));
        BigDecimal tax1Amount = amount(netAmount.multiply(tax1Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
        BigDecimal tax2Amount = amount(netAmount.multiply(tax2Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));

        Invoice invoice = Invoice.builder()
                .tenantId(paymentIntent.getTenantId())
                .invoiceType(InvoiceType.DEPOSIT)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(LocalDate.now())
                .customerName(firstNonBlank(request.getCustomerName(),
                        reservations.stream().map(Reservation::getCustomerName).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerEmail(firstNonBlank(request.getCustomerEmail(),
                        reservations.stream().map(Reservation::getCustomerEmail).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerPhone(firstNonBlank(request.getCustomerPhone(),
                        reservations.stream().map(Reservation::getCustomerPhone).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .issuedByMode(IssuedByMode.ONLINE_SYSTEM)
                .issuedAt(OffsetDateTime.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("PAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .referenceTable(REFERENCE_TABLE_PAYMENT_INTENT)
                .referenceId(paymentIntent.getId())
                .reservationRequestId(requestId)
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .currency(paymentIntent.getCurrency())
                .subtotalNet(money(netAmount))
                .discountTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax1Total(money(tax1Amount))
                .tax2Total(money(tax2Amount))
                .totalGross(grossAmount)
                .build();
        invoice = invoiceRepo.save(invoice);

        InvoiceItem item = InvoiceItem.builder()
                .invoice(invoice)
                .lineNo(1)
                .reservationId(null)
                .productId(depositProduct.getId())
                .productName(depositProduct.getName())
                .quantity(1)
                .unitPriceGross(grossAmount)
                .discountPercent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .discountAmount(amountZero())
                .priceWithoutTax(netAmount)
                .tax1Percent(tax1Percent.setScale(4, RoundingMode.HALF_UP))
                .tax2Percent(tax2Percent.setScale(4, RoundingMode.HALF_UP))
                .tax1Amount(tax1Amount)
                .tax2Amount(tax2Amount)
                .nettPrice(netAmount)
                .grossAmount(grossAmount)
                .build();
        invoiceItemRepo.save(item);

        PaymentTransaction paymentTransaction = paymentTransactionService.ensureForPaidIntent(paymentIntent);
        allocationRepo.save(InvoicePaymentAllocation.builder()
                .invoice(invoice)
                .paymentTransaction(paymentTransaction)
                .allocatedAmount(grossAmount)
                .build());

        refreshInvoicePaymentStatus(invoice);
        return invoice;
    }

    @Transactional
    public Invoice createStornoInvoice(Long invoiceId) {
        Invoice source = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        if (source.getStornoId() != null) {
            return invoiceRepo.findById(source.getStornoId())
                    .orElseThrow(() -> new IllegalStateException("Storno invoice not found"));
        }
        if (isStornoType(source.getInvoiceType())) {
            throw new IllegalStateException("Storno cannot be created for storno invoice");
        }

        InvoiceType stornoType = stornoTypeFor(source.getInvoiceType());
        int year = LocalDate.now().getYear();
        int seq = nextSequence(source.getTenantId(), stornoType, year);
        String invoiceNumber = buildNumber(stornoType, year, seq);

        Invoice storno = Invoice.builder()
                .tenantId(source.getTenantId())
                .invoiceType(stornoType)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(LocalDate.now())
                .customerName(source.getCustomerName())
                .customerEmail(source.getCustomerEmail())
                .customerPhone(source.getCustomerPhone())
                .issuedByMode(IssuedByMode.ONLINE_SYSTEM)
                .issuedAt(OffsetDateTime.now())
                .status(InvoiceStatus.ISSUED)
                .paymentStatus("UNPAID")
                .fiscalizationStatus(InvoiceFiscalizationStatus.REQUIRED)
                .referenceTable(REFERENCE_TABLE_INVOICE)
                .referenceId(source.getId())
                .reservationRequestId(source.getReservationRequestId())
                .operaReservationId(source.getOperaReservationId())
                .operaHotelCode(source.getOperaHotelCode())
                .operaPostingStatus(OperaPostingStatus.NOT_POSTED)
                .currency(source.getCurrency())
                .subtotalNet(money(negate(source.getSubtotalNet())))
                .discountTotal(money(negate(source.getDiscountTotal())))
                .tax1Total(money(negate(source.getTax1Total())))
                .tax2Total(money(negate(source.getTax2Total())))
                .totalGross(money(negate(source.getTotalGross())))
                .build();
        storno = invoiceRepo.save(storno);

        for (InvoiceItem sourceItem : invoiceItemRepo.findByInvoiceIdOrderByLineNoAsc(source.getId())) {
            invoiceItemRepo.save(InvoiceItem.builder()
                    .invoice(storno)
                    .lineNo(sourceItem.getLineNo())
                    .reservationId(sourceItem.getReservationId())
                    .productId(sourceItem.getProductId())
                    .productName(sourceItem.getProductName())
                    .quantity(-sourceItem.getQuantity())
                    .unitPriceGross(negate(sourceItem.getUnitPriceGross()))
                    .discountPercent(sourceItem.getDiscountPercent())
                    .discountAmount(negate(sourceItem.getDiscountAmount()))
                    .priceWithoutTax(negate(sourceItem.getPriceWithoutTax()))
                    .tax1Percent(sourceItem.getTax1Percent())
                    .tax2Percent(sourceItem.getTax2Percent())
                    .tax1Amount(negate(sourceItem.getTax1Amount()))
                    .tax2Amount(negate(sourceItem.getTax2Amount()))
                    .nettPrice(negate(sourceItem.getNettPrice()))
                    .grossAmount(negate(sourceItem.getGrossAmount()))
                    .build());
        }

        for (InvoicePaymentAllocation sourceAllocation : allocationRepo.findByInvoiceIdOrderByCreatedAtAsc(source.getId())) {
            allocationRepo.save(InvoicePaymentAllocation.builder()
                    .invoice(storno)
                    .paymentTransaction(sourceAllocation.getPaymentTransaction())
                    .allocatedAmount(negate(sourceAllocation.getAllocatedAmount()))
                    .build());
        }

        source.setStornoId(storno.getId());
        invoiceRepo.save(source);
        refreshInvoicePaymentStatus(storno);
        return storno;
    }

    @Transactional
    public InvoicePaymentAllocation allocatePaymentToInvoice(Long invoiceId,
                                                             Long paymentTransactionId,
                                                             BigDecimal amount) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        if (paymentTransactionId == null) {
            throw new IllegalArgumentException("paymentTransactionId is required");
        }
        PaymentTransaction paymentTransaction = paymentTransactionService.requireById(paymentTransactionId);
        if (!invoice.getTenantId().equals(paymentTransaction.getTenantId())) {
            throw new IllegalArgumentException("Invoice and payment transaction tenant mismatch");
        }
        if (!"POSTED".equalsIgnoreCase(paymentTransaction.getStatus())) {
            throw new IllegalStateException("Only POSTED payment transactions can be allocated");
        }

        BigDecimal alreadyAllocated = zeroSafe(allocationRepo.sumAllocatedByPaymentTransactionId(paymentTransactionId));
        BigDecimal existingAmount = allocationRepo.findByInvoiceIdAndPaymentTransactionId(invoiceId, paymentTransactionId)
                .map(InvoicePaymentAllocation::getAllocatedAmount)
                .orElse(BigDecimal.ZERO);
        BigDecimal available = money(zeroSafe(paymentTransaction.getAmount()).subtract(alreadyAllocated).add(existingAmount));

        BigDecimal allocationAmount = amount != null ? money(amount) : available;
        if (allocationAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Allocation amount must be greater than zero");
        }
        if (allocationAmount.compareTo(available) > 0) {
            throw new IllegalArgumentException("Allocation amount exceeds available payment amount");
        }

        PaymentTransaction linkedPaymentTransaction = paymentTransaction;
        InvoicePaymentAllocation allocation = allocationRepo.findByInvoiceIdAndPaymentTransactionId(invoiceId, paymentTransactionId)
                .orElseGet(() -> InvoicePaymentAllocation.builder()
                        .invoice(invoice)
                        .paymentTransaction(linkedPaymentTransaction)
                        .allocatedAmount(BigDecimal.ZERO)
                        .build());
        allocation.setAllocatedAmount(allocationAmount);
        InvoicePaymentAllocation saved = allocationRepo.save(allocation);

        refreshInvoicePaymentStatus(invoice);
        return saved;
    }

    private void replaceItemsAndRecalculateTotals(Invoice invoice, List<InvoiceCreateItemRequest> requestedItems) {
        invoiceItemRepo.deleteByInvoiceId(invoice.getId());

        if (requestedItems.isEmpty()) {
            invoice.setSubtotalNet(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            invoice.setDiscountTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            invoice.setTax1Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            invoice.setTax2Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            invoice.setTotalGross(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
            return;
        }

        BigDecimal subtotalNet = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal tax1Total = BigDecimal.ZERO;
        BigDecimal tax2Total = BigDecimal.ZERO;
        BigDecimal totalGross = BigDecimal.ZERO;
        int lineNo = 1;

        LocalDate invoiceDate = invoice.getInvoiceDate() != null ? invoice.getInvoiceDate() : LocalDate.now();
        String currency = normalizeCurrency(invoice.getCurrency());

        for (InvoiceCreateItemRequest itemRequest : requestedItems) {
            if (itemRequest == null) {
                throw new IllegalArgumentException("items must not contain null elements");
            }

            Product product = resolveProductForItem(invoice.getTenantId(), itemRequest.getProductId());
            int quantity = normalizeQuantity(itemRequest.getQuantity());
            String productName = resolveItemDescription(itemRequest, product);
            BigDecimal tax1Percent = normalizePercent(firstNonNull(itemRequest.getTax1Percent(), product != null ? product.getTax1Percent() : null));
            BigDecimal tax2Percent = normalizePercent(firstNonNull(itemRequest.getTax2Percent(), product != null ? product.getTax2Percent() : null));
            BigDecimal unitPriceGross = resolveUnitPriceGross(itemRequest, product, invoice.getTenantId(), currency, invoiceDate, quantity);
            BigDecimal discountPercent = normalizeDiscountPercent(itemRequest.getDiscountPercent());

            BigDecimal grossBeforeDiscount = amount(unitPriceGross.multiply(BigDecimal.valueOf(quantity)));
            BigDecimal discountAmount = amount(grossBeforeDiscount.multiply(discountPercent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal grossAmount = amount(grossBeforeDiscount.subtract(discountAmount));
            if (grossAmount.compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("item gross amount after discount cannot be negative");
            }

            BigDecimal totalTaxPercent = tax1Percent.add(tax2Percent);
            BigDecimal divisor = BigDecimal.ONE.add(totalTaxPercent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal net = amount(grossAmount.divide(divisor, 8, RoundingMode.HALF_UP));
            BigDecimal tax1Amount = amount(net.multiply(tax1Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal tax2Amount = amount(net.multiply(tax2Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .lineNo(lineNo++)
                    .reservationId(null)
                    .productId(product != null ? product.getId() : itemRequest.getProductId())
                    .productName(productName)
                    .quantity(quantity)
                    .unitPriceGross(unitPriceGross)
                    .discountPercent(discountPercent.setScale(4, RoundingMode.HALF_UP))
                    .discountAmount(discountAmount)
                    .priceWithoutTax(net)
                    .tax1Percent(tax1Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax2Percent(tax2Percent.setScale(4, RoundingMode.HALF_UP))
                    .tax1Amount(tax1Amount)
                    .tax2Amount(tax2Amount)
                    .nettPrice(net)
                    .grossAmount(grossAmount)
                    .build();
            invoiceItemRepo.save(item);

            subtotalNet = subtotalNet.add(net);
            discountTotal = discountTotal.add(discountAmount);
            tax1Total = tax1Total.add(tax1Amount);
            tax2Total = tax2Total.add(tax2Amount);
            totalGross = totalGross.add(grossAmount);
        }

        invoice.setSubtotalNet(money(subtotalNet));
        invoice.setDiscountTotal(money(discountTotal));
        invoice.setTax1Total(money(tax1Total));
        invoice.setTax2Total(money(tax2Total));
        invoice.setTotalGross(money(totalGross));
    }

    @Transactional
    public void removePaymentAllocation(Long invoiceId, Long paymentTransactionId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        InvoicePaymentAllocation allocation = allocationRepo.findByInvoiceIdAndPaymentTransactionId(invoiceId, paymentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Payment allocation not found"));
        allocationRepo.delete(allocation);
        refreshInvoicePaymentStatus(invoice);
    }

    private void refreshInvoicePaymentStatus(Invoice invoice) {
        BigDecimal total = money(invoice.getTotalGross() != null ? invoice.getTotalGross() : BigDecimal.ZERO);
        BigDecimal allocated = money(zeroSafe(allocationRepo.sumAllocatedByInvoiceId(invoice.getId())));
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            invoice.setPaymentStatus("PAID");
        } else if (allocated.compareTo(BigDecimal.ZERO) == 0 || allocated.signum() != total.signum()) {
            invoice.setPaymentStatus("UNPAID");
        } else if (allocated.abs().compareTo(total.abs()) >= 0) {
            invoice.setPaymentStatus("PAID");
        } else {
            invoice.setPaymentStatus("PARTIALLY_PAID");
        }
        invoiceRepo.save(invoice);
    }

    private int nextSequence(Long tenantId, InvoiceType invoiceType, int year) {
        String key = invoiceType.name();
        InvoiceSequence sequence = sequenceRepo.findForUpdate(tenantId, key, year)
                .orElseGet(() -> InvoiceSequence.builder()
                        .tenantId(tenantId)
                        .invoiceType(key)
                        .invoiceYear(year)
                        .lastNumber(0)
                        .build());
        sequence.setLastNumber(sequence.getLastNumber() + 1);
        return sequenceRepo.save(sequence).getLastNumber();
    }

    private String buildNumber(InvoiceType invoiceType, int year, int seq) {
        return invoiceType.name() + "-" + year + "-" + String.format("%05d", seq);
    }

    private int quantity(Reservation reservation) {
        return reservation.getQty() != null && reservation.getQty() > 0 ? reservation.getQty() : 1;
    }

    private BigDecimal unitPrice(Reservation reservation) {
        return reservation.getUnitPrice() != null ? reservation.getUnitPrice() : BigDecimal.ZERO;
    }

    private BigDecimal percent(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal amount(BigDecimal value) {
        return value;
    }

    private BigDecimal amountZero() {
        return BigDecimal.ZERO;
    }

    private BigDecimal zeroSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean isStornoType(InvoiceType invoiceType) {
        return invoiceType != null && invoiceType.isStornoType();
    }

    private InvoiceType stornoTypeFor(InvoiceType sourceType) {
        if (sourceType == InvoiceType.INVOICE || sourceType == InvoiceType.ROOM_CHARGE) {
            return InvoiceType.INVOICE_STORNO;
        }
        if (sourceType == InvoiceType.DEPOSIT) {
            return InvoiceType.DEPOSIT_STORNO;
        }
        throw new IllegalArgumentException("Unsupported invoice type for storno: " + sourceType);
    }

    private BigDecimal negate(BigDecimal value) {
        return zeroSafe(value).negate();
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }

    private Long normalizeOperaReservationId(Long operaReservationId) {
        if (operaReservationId == null) {
            return null;
        }
        if (operaReservationId <= 0) {
            return null;
        }
        return operaReservationId;
    }

    private String normalizeOperaHotelCode(String operaHotelCode) {
        if (!StringUtils.hasText(operaHotelCode)) {
            return null;
        }
        return operaHotelCode.trim().toUpperCase(Locale.ROOT);
    }

    private InvoiceType normalizeInvoiceType(InvoiceType invoiceType) {
        return invoiceType != null ? invoiceType : InvoiceType.INVOICE;
    }

    private String normalizeCurrency(String currency) {
        if (!StringUtils.hasText(currency)) {
            return DEFAULT_CURRENCY;
        }
        return currency.trim().toUpperCase(Locale.ROOT);
    }

    private Product resolveProductForItem(Long tenantId, Long productId) {
        if (productId == null) {
            throw new IllegalArgumentException("item productId is required");
        }
        return productRepo.findByIdAndTenantId(productId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("productId " + productId + " not found for tenant"));
    }

    private int normalizeQuantity(Integer quantity) {
        if (quantity == null) {
            return 1;
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("item quantity must be greater than zero");
        }
        return quantity;
    }

    private String resolveItemDescription(InvoiceCreateItemRequest itemRequest, Product product) {
        String value = firstNonBlank(itemRequest.getDescription(),
                firstNonBlank(itemRequest.getProductName(), product != null ? product.getName() : null));
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("item description is required when product cannot provide a name");
        }
        return value.trim();
    }

    private BigDecimal resolveUnitPriceGross(InvoiceCreateItemRequest itemRequest,
                                             Product product,
                                             Long tenantId,
                                             String currency,
                                             LocalDate invoiceDate,
                                             int quantity) {
        if (itemRequest.getUnitPriceGross() != null) {
            if (itemRequest.getUnitPriceGross().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("item unitPriceGross cannot be negative");
            }
            return amount(itemRequest.getUnitPriceGross());
        }

        if (itemRequest.getGrossAmount() != null) {
            if (itemRequest.getGrossAmount().compareTo(BigDecimal.ZERO) < 0) {
                throw new IllegalArgumentException("item grossAmount cannot be negative");
            }
            return amount(itemRequest.getGrossAmount().divide(BigDecimal.valueOf(quantity), 8, RoundingMode.HALF_UP));
        }

        if (product == null) {
            throw new IllegalArgumentException("item unitPriceGross is required when productId is not provided");
        }

        String uom = firstNonBlank(itemRequest.getUom(), product.getDefaultUom());
        if (!StringUtils.hasText(uom)) {
            throw new IllegalArgumentException("item uom is required for price lookup");
        }

        List<PriceListEntry> entries = priceListRepo.findForProductUomOnDate(
                product.getId(),
                uom.trim(),
                currency,
                tenantId,
                invoiceDate
        );
        if (entries.isEmpty() || entries.get(0).getPrice() == null) {
            throw new IllegalArgumentException("unitPriceGross is required: no price list entry found for productId " + product.getId());
        }
        BigDecimal price = entries.get(0).getPrice();
        if (price.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("price list unitPriceGross cannot be negative for productId " + product.getId());
        }
        return amount(price);
    }

    private BigDecimal firstNonNull(BigDecimal primary, BigDecimal fallback) {
        return primary != null ? primary : fallback;
    }

    private BigDecimal normalizePercent(BigDecimal value) {
        BigDecimal resolved = value != null ? value : BigDecimal.ZERO;
        if (resolved.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("tax percent cannot be negative");
        }
        return resolved;
    }

    private BigDecimal normalizeDiscountPercent(BigDecimal value) {
        BigDecimal resolved = value != null ? value : BigDecimal.ZERO;
        if (resolved.compareTo(BigDecimal.ZERO) < 0 || resolved.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("discountPercent must be between 0 and 100");
        }
        return resolved;
    }

    private IssuedByMode normalizeIssuedByMode(IssuedByMode issuedByMode) {
        return issuedByMode != null ? issuedByMode : IssuedByMode.CASHIER;
    }

    private Long resolveIssuedByUserId(Long tenantId, IssuedByMode issuedByMode, Long requestedIssuedByUserId) {
        Optional<AppUser> currentUser = resolveAuthenticatedAppUser();
        if (issuedByMode == IssuedByMode.CASHIER) {
            AppUser issuer = currentUser
                    .orElseThrow(() -> new IllegalStateException("Cashier issuance requires authenticated app user"));
            ensureUserInTenant(issuer, tenantId);
            return issuer.getId();
        }

        if (requestedIssuedByUserId != null) {
            AppUser requestedIssuer = appUserRepo.findByIdAndTenantId(requestedIssuedByUserId, tenantId)
                    .orElseThrow(() -> new IllegalArgumentException("issuedByUserId not found for tenant"));
            return requestedIssuer.getId();
        }

        if (issuedByMode == IssuedByMode.ONLINE_SYSTEM) {
            Optional<AppUser> onlineSystemUser = appUserRepo.findByTenantIdAndUsername(
                    tenantId,
                    onlineSystemUsername(tenantId)
            );
            if (onlineSystemUser.isPresent()) {
                return onlineSystemUser.get().getId();
            }
        }

        if (currentUser.isPresent()) {
            AppUser issuer = currentUser.get();
            if (tenantId.equals(issuer.getTenantId())) {
                return issuer.getId();
            }
        }
        return null;
    }

    private String onlineSystemUsername(Long tenantId) {
        return ONLINE_SYSTEM_USERNAME_PREFIX + tenantId;
    }

    private Optional<AppUser> resolveAuthenticatedAppUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }

        Object principal = authentication.getPrincipal();
        if (!(principal instanceof String username) || !StringUtils.hasText(username)) {
            return Optional.empty();
        }
        if (username.startsWith("api-token:")) {
            return Optional.empty();
        }
        return appUserRepo.findByUsername(username);
    }

    private void ensureUserInTenant(AppUser user, Long tenantId) {
        if (user.getTenantId() == null || !tenantId.equals(user.getTenantId())) {
            throw new IllegalStateException("Authenticated user does not belong to invoice tenant");
        }
    }

    private InvoiceSearchCriteria normalizeAndValidate(InvoiceSearchCriteria criteria) {
        InvoiceSearchCriteria resolved = criteria != null ? criteria : new InvoiceSearchCriteria();

        resolved.setTenantId(TenantResolver.resolveTenantId(resolved.getTenantId()));

        if (resolved.getReservationRequestId() == null) {
            if (resolved.getReservation_reqst() != null) {
                resolved.setReservationRequestId(resolved.getReservation_reqst());
            } else if (resolved.getReservation_request() != null) {
                resolved.setReservationRequestId(resolved.getReservation_request());
            }
        }

        resolved.setInvoiceNumber(normalizeNullable(resolved.getInvoiceNumber()));
        resolved.setReferenceTable(normalizeNullable(resolved.getReferenceTable()));
        resolved.setCustomer(normalizeNullable(resolved.getCustomer()));
        resolved.setCustomerName(normalizeNullable(resolved.getCustomerName()));
        resolved.setCustomerEmail(normalizeNullable(resolved.getCustomerEmail()));
        resolved.setCustomerPhone(normalizeNullable(resolved.getCustomerPhone()));

        resolved.setInvoiceTypes(normalizeUpperValues(resolved.getInvoiceTypes()));
        resolved.setStatuses(normalizeUpperValues(resolved.getStatuses()));
        resolved.setPaymentStatuses(normalizeUpperValues(resolved.getPaymentStatuses()));
        resolved.setFiscalizationStatuses(normalizeUpperValues(resolved.getFiscalizationStatuses()));
        resolved.setCurrencies(normalizeUpperValues(resolved.getCurrencies()));

        validateRange("invoiceDate", resolved.getInvoiceDateFrom(), resolved.getInvoiceDateTo());
        validateRange("createdAt", resolved.getCreatedFrom(), resolved.getCreatedTo());
        validateDecimalRange("totalGross", resolved.getTotalGrossMin(), resolved.getTotalGrossMax());

        return resolved;
    }

    private List<String> normalizeUpperValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(v -> v.trim().toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private <T extends Comparable<? super T>> void validateRange(String fieldName, T from, T to) {
        if (from != null && to != null && from.compareTo(to) > 0) {
            throw new IllegalArgumentException(fieldName + " range is invalid: from must be <= to");
        }
    }

    private void validateDecimalRange(String fieldName, BigDecimal min, BigDecimal max) {
        if (min != null && max != null && min.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldName + " range is invalid: min must be <= max");
        }
    }
}
