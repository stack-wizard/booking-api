package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.InvoiceSearchCriteria;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceSequence;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.InvoiceSequenceRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.repository.specification.InvoiceSpecifications;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class InvoiceService {
    private static final String INVOICE_TYPE_STANDARD = "INVOICE";
    private static final String INVOICE_TYPE_DEPOSIT = "DEPOSIT";
    private static final String INVOICE_TYPE_STANDARD_STORNO = "INVOICE_STORNO";
    private static final String INVOICE_TYPE_DEPOSIT_STORNO = "DEPOSIT_STORNO";
    private static final String PRODUCT_TYPE_DEPOSIT = "DEPOSIT";
    private static final String REFERENCE_TABLE_RESERVATION_REQUEST = "reservation_request";
    private static final String REFERENCE_TABLE_PAYMENT_INTENT = "payment_intent";
    private static final String REFERENCE_TABLE_INVOICE = "invoice";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final InvoiceSequenceRepository sequenceRepo;
    private final PaymentTransactionService paymentTransactionService;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final ProductRepository productRepo;

    public InvoiceService(InvoiceRepository invoiceRepo,
                          InvoiceItemRepository invoiceItemRepo,
                          InvoicePaymentAllocationRepository allocationRepo,
                          InvoiceSequenceRepository sequenceRepo,
                          PaymentTransactionService paymentTransactionService,
                          ReservationRequestRepository requestRepo,
                          ReservationRepository reservationRepo,
                          ProductRepository productRepo) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.sequenceRepo = sequenceRepo;
        this.paymentTransactionService = paymentTransactionService;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.productRepo = productRepo;
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
            if (INVOICE_TYPE_DEPOSIT.equalsIgnoreCase(invoice.getInvoiceType())) {
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
        int seq = nextSequence(request.getTenantId(), INVOICE_TYPE_STANDARD, year);
        String invoiceNumber = buildNumber(INVOICE_TYPE_STANDARD, year, seq);
        String currency = reservations.stream()
                .map(Reservation::getCurrency)
                .filter(c -> c != null && !c.isBlank())
                .findFirst()
                .orElse("EUR");

        Invoice invoice = Invoice.builder()
                .tenantId(request.getTenantId())
                .invoiceType(INVOICE_TYPE_STANDARD)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(LocalDate.now())
                .customerName(firstNonBlank(request.getCustomerName(),
                        reservations.stream().map(Reservation::getCustomerName).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerEmail(firstNonBlank(request.getCustomerEmail(),
                        reservations.stream().map(Reservation::getCustomerEmail).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .customerPhone(firstNonBlank(request.getCustomerPhone(),
                        reservations.stream().map(Reservation::getCustomerPhone).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null)))
                .status("DRAFT")
                .paymentStatus("UNPAID")
                .fiscalizationStatus("NOT_REQUIRED")
                .referenceTable(REFERENCE_TABLE_RESERVATION_REQUEST)
                .referenceId(requestId)
                .reservationRequestId(requestId)
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
        Product depositProduct = productRepo.findFirstByTenantIdAndProductTypeIgnoreCaseOrderByIdAsc(
                        paymentIntent.getTenantId(), PRODUCT_TYPE_DEPOSIT)
                .orElseThrow(() -> new IllegalStateException("Deposit product is missing for tenant " + paymentIntent.getTenantId()));

        int year = LocalDate.now().getYear();
        int seq = nextSequence(paymentIntent.getTenantId(), INVOICE_TYPE_DEPOSIT, year);
        String invoiceNumber = buildNumber(INVOICE_TYPE_DEPOSIT, year, seq);
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
                .invoiceType(INVOICE_TYPE_DEPOSIT)
                .invoiceNumber(invoiceNumber)
                .invoiceDate(LocalDate.now())
                .customerName(request.getCustomerName())
                .customerEmail(request.getCustomerEmail())
                .customerPhone(request.getCustomerPhone())
                .status("ISSUED")
                .paymentStatus("PAID")
                .fiscalizationStatus("NOT_REQUIRED")
                .referenceTable(REFERENCE_TABLE_PAYMENT_INTENT)
                .referenceId(paymentIntent.getId())
                .reservationRequestId(requestId)
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

        String stornoType = stornoTypeFor(source.getInvoiceType());
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
                .status("ISSUED")
                .paymentStatus("UNPAID")
                .fiscalizationStatus("NOT_REQUIRED")
                .referenceTable(REFERENCE_TABLE_INVOICE)
                .referenceId(source.getId())
                .reservationRequestId(source.getReservationRequestId())
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

    private int nextSequence(Long tenantId, String invoiceType, int year) {
        InvoiceSequence sequence = sequenceRepo.findForUpdate(tenantId, invoiceType, year)
                .orElseGet(() -> InvoiceSequence.builder()
                        .tenantId(tenantId)
                        .invoiceType(invoiceType)
                        .invoiceYear(year)
                        .lastNumber(0)
                        .build());
        sequence.setLastNumber(sequence.getLastNumber() + 1);
        return sequenceRepo.save(sequence).getLastNumber();
    }

    private String buildNumber(String invoiceType, int year, int seq) {
        return invoiceType + "-" + year + "-" + String.format("%05d", seq);
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

    private boolean isStornoType(String invoiceType) {
        return INVOICE_TYPE_STANDARD_STORNO.equalsIgnoreCase(invoiceType)
                || INVOICE_TYPE_DEPOSIT_STORNO.equalsIgnoreCase(invoiceType);
    }

    private String stornoTypeFor(String sourceType) {
        if (INVOICE_TYPE_STANDARD.equalsIgnoreCase(sourceType)) {
            return INVOICE_TYPE_STANDARD_STORNO;
        }
        if (INVOICE_TYPE_DEPOSIT.equalsIgnoreCase(sourceType)) {
            return INVOICE_TYPE_DEPOSIT_STORNO;
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
