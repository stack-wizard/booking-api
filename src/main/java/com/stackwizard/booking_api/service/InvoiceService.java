package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoiceItem;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.InvoiceSequence;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.InvoiceItemRepository;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.InvoiceRepository;
import com.stackwizard.booking_api.repository.InvoiceSequenceRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class InvoiceService {
    private static final String INVOICE_TYPE_STANDARD = "INVOICE";
    private static final String INVOICE_TYPE_DEPOSIT = "DEPOSIT";
    private static final String REFERENCE_TABLE_RESERVATION_REQUEST = "reservation_request";
    private static final String REFERENCE_TABLE_PAYMENT_INTENT = "payment_intent";
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final InvoiceRepository invoiceRepo;
    private final InvoiceItemRepository invoiceItemRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final InvoiceSequenceRepository sequenceRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final ProductRepository productRepo;

    public InvoiceService(InvoiceRepository invoiceRepo,
                          InvoiceItemRepository invoiceItemRepo,
                          InvoicePaymentAllocationRepository allocationRepo,
                          InvoiceSequenceRepository sequenceRepo,
                          PaymentIntentRepository paymentIntentRepo,
                          ReservationRequestRepository requestRepo,
                          ReservationRepository reservationRepo,
                          ProductRepository productRepo) {
        this.invoiceRepo = invoiceRepo;
        this.invoiceItemRepo = invoiceItemRepo;
        this.allocationRepo = allocationRepo;
        this.sequenceRepo = sequenceRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.productRepo = productRepo;
    }

    public Optional<Invoice> findByReference(String referenceTable, Long referenceId) {
        return invoiceRepo.findByReferenceTableAndReferenceId(referenceTable, referenceId);
    }

    public Optional<Invoice> findById(Long id) {
        return invoiceRepo.findById(id);
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
            BigDecimal grossAmount = money(
                    reservation.getGrossAmount() != null
                            ? reservation.getGrossAmount()
                            : unitPrice(reservation).multiply(BigDecimal.valueOf(quantity(reservation))));
            BigDecimal unitPriceGross = money(unitPrice(reservation));
            BigDecimal tax1Percent = percent(product != null ? product.getTax1Percent() : null);
            BigDecimal tax2Percent = percent(product != null ? product.getTax2Percent() : null);

            BigDecimal totalTaxPercent = tax1Percent.add(tax2Percent);
            BigDecimal divisor = BigDecimal.ONE.add(totalTaxPercent.divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal net = money(grossAmount.divide(divisor, 8, RoundingMode.HALF_UP));
            BigDecimal tax1Amount = money(net.multiply(tax1Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));
            BigDecimal tax2Amount = money(net.multiply(tax2Percent).divide(HUNDRED, 8, RoundingMode.HALF_UP));

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .lineNo(lineNo++)
                    .reservationId(reservation.getId())
                    .productId(reservation.getProductId())
                    .productName(product != null ? product.getName() : "Product " + reservation.getProductId())
                    .quantity(quantity(reservation))
                    .unitPriceGross(unitPriceGross)
                    .discountPercent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                    .discountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
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

        int year = LocalDate.now().getYear();
        int seq = nextSequence(paymentIntent.getTenantId(), INVOICE_TYPE_DEPOSIT, year);
        String invoiceNumber = buildNumber(INVOICE_TYPE_DEPOSIT, year, seq);
        BigDecimal amount = money(paymentIntent.getAmount());

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
                .currency(paymentIntent.getCurrency())
                .subtotalNet(amount)
                .discountTotal(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax1Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax2Total(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .totalGross(amount)
                .build();
        invoice = invoiceRepo.save(invoice);

        InvoiceItem item = InvoiceItem.builder()
                .invoice(invoice)
                .lineNo(1)
                .reservationId(null)
                .productId(null)
                .productName("Deposit payment for reservation request #" + requestId)
                .quantity(1)
                .unitPriceGross(amount)
                .discountPercent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .discountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .priceWithoutTax(amount)
                .tax1Percent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .tax2Percent(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP))
                .tax1Amount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .tax2Amount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
                .nettPrice(amount)
                .grossAmount(amount)
                .build();
        invoiceItemRepo.save(item);

        allocationRepo.save(InvoicePaymentAllocation.builder()
                .invoice(invoice)
                .paymentIntent(paymentIntent)
                .allocatedAmount(amount)
                .build());

        refreshInvoicePaymentStatus(invoice);
        return invoice;
    }

    @Transactional
    public InvoicePaymentAllocation allocatePaymentToInvoice(Long invoiceId, Long paymentIntentId, BigDecimal amount) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        PaymentIntent paymentIntent = paymentIntentRepo.findById(paymentIntentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));

        if (!invoice.getTenantId().equals(paymentIntent.getTenantId())) {
            throw new IllegalArgumentException("Invoice and payment intent tenant mismatch");
        }
        if (!"PAID".equalsIgnoreCase(paymentIntent.getStatus())) {
            throw new IllegalStateException("Only PAID payment intents can be allocated");
        }

        BigDecimal alreadyAllocated = zeroSafe(allocationRepo.sumAllocatedByPaymentIntentId(paymentIntentId));
        BigDecimal existingAmount = allocationRepo.findByInvoiceIdAndPaymentIntentId(invoiceId, paymentIntentId)
                .map(InvoicePaymentAllocation::getAllocatedAmount)
                .orElse(BigDecimal.ZERO);
        BigDecimal available = money(paymentIntent.getAmount().subtract(alreadyAllocated).add(existingAmount));

        BigDecimal allocationAmount = amount != null ? money(amount) : available;
        if (allocationAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Allocation amount must be greater than zero");
        }
        if (allocationAmount.compareTo(available) > 0) {
            throw new IllegalArgumentException("Allocation amount exceeds available payment amount");
        }

        InvoicePaymentAllocation allocation = allocationRepo.findByInvoiceIdAndPaymentIntentId(invoiceId, paymentIntentId)
                .orElseGet(() -> InvoicePaymentAllocation.builder()
                        .invoice(invoice)
                        .paymentIntent(paymentIntent)
                        .allocatedAmount(BigDecimal.ZERO)
                        .build());
        allocation.setAllocatedAmount(allocationAmount);
        InvoicePaymentAllocation saved = allocationRepo.save(allocation);

        refreshInvoicePaymentStatus(invoice);
        return saved;
    }

    @Transactional
    public void removePaymentAllocation(Long invoiceId, Long paymentIntentId) {
        Invoice invoice = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Invoice not found"));
        InvoicePaymentAllocation allocation = allocationRepo.findByInvoiceIdAndPaymentIntentId(invoiceId, paymentIntentId)
                .orElseThrow(() -> new IllegalArgumentException("Payment allocation not found"));
        allocationRepo.delete(allocation);
        refreshInvoicePaymentStatus(invoice);
    }

    private void refreshInvoicePaymentStatus(Invoice invoice) {
        BigDecimal total = money(invoice.getTotalGross() != null ? invoice.getTotalGross() : BigDecimal.ZERO);
        BigDecimal allocated = money(zeroSafe(allocationRepo.sumAllocatedByInvoiceId(invoice.getId())));
        if (allocated.compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setPaymentStatus("UNPAID");
        } else if (allocated.compareTo(total) >= 0) {
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

    private BigDecimal zeroSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (primary != null && !primary.isBlank()) {
            return primary;
        }
        return fallback;
    }
}
