package com.stackwizard.booking_api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.dto.PaymentTransactionDto;
import com.stackwizard.booking_api.dto.PaymentTransactionSearchCriteria;
import com.stackwizard.booking_api.model.PaymentEvent;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
import com.stackwizard.booking_api.repository.PaymentEventRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.PaymentTransactionRepository;
import com.stackwizard.booking_api.repository.specification.PaymentTransactionSpecifications;
import com.stackwizard.booking_api.security.TenantResolver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
public class PaymentTransactionService {
    private static final String STATUS_POSTED = "POSTED";
    private static final String TRANSACTION_TYPE_CHARGE = "CHARGE";
    private static final String TRANSACTION_TYPE_REFUND = "REFUND";
    private static final String PAYMENT_TYPE_CARD = "CARD";
    private static final String PAYMENT_TYPE_BANK_TRANSFER = "BANK_TRANSFER";
    private static final Set<String> SUPPORTED_PAYMENT_TYPES = Set.of(
            "CASH",
            PAYMENT_TYPE_CARD,
            PAYMENT_TYPE_BANK_TRANSFER,
            "ROOM_CHARGE"
    );
    private static final Set<String> SUPPORTED_STATUSES = Set.of(
            STATUS_POSTED,
            "VOIDED"
    );
    private static final Set<String> SUPPORTED_TRANSACTION_TYPES = Set.of(
            TRANSACTION_TYPE_CHARGE,
            TRANSACTION_TYPE_REFUND
    );
    private static final Set<String> SUPPORTED_REFUND_TYPES = Set.of(
            "CANCELLATION",
            "MANUAL"
    );

    private final PaymentTransactionRepository paymentTransactionRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final PaymentEventRepository paymentEventRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;
    private final PaymentCardTypeService paymentCardTypeService;

    public PaymentTransactionService(PaymentTransactionRepository paymentTransactionRepo,
                                     PaymentIntentRepository paymentIntentRepo,
                                     PaymentEventRepository paymentEventRepo,
                                     InvoicePaymentAllocationRepository allocationRepo,
                                     PaymentCardTypeService paymentCardTypeService) {
        this.paymentTransactionRepo = paymentTransactionRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.paymentEventRepo = paymentEventRepo;
        this.allocationRepo = allocationRepo;
        this.paymentCardTypeService = paymentCardTypeService;
    }

    @Transactional(readOnly = true)
    public Page<PaymentTransactionDto> search(PaymentTransactionSearchCriteria criteria, Pageable pageable) {
        PaymentTransactionSearchCriteria normalized = normalizeAndValidate(criteria);
        Page<PaymentTransaction> page = paymentTransactionRepo.findAll(PaymentTransactionSpecifications.byCriteria(normalized), pageable);
        List<Long> transactionIds = page.getContent().stream().map(PaymentTransaction::getId).toList();
        Map<Long, BigDecimal> allocatedByTransactionId = allocatedByTransactionId(transactionIds);
        Map<Long, BigDecimal> refundedBySourceTransactionId = refundedBySourceTransactionId(transactionIds);
        return page.map(tx -> toDto(
                tx,
                allocatedByTransactionId.getOrDefault(tx.getId(), BigDecimal.ZERO),
                refundedBySourceTransactionId.getOrDefault(tx.getId(), BigDecimal.ZERO)
        ));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentTransactionDto> findById(Long id) {
        return paymentTransactionRepo.findById(id)
                .map(tx -> toDto(
                        tx,
                        zeroSafe(allocationRepo.sumAllocatedByPaymentTransactionId(tx.getId())),
                        refundedAmountForSourcePaymentTransaction(tx.getId())
                ));
    }

    @Transactional(readOnly = true)
    public PaymentTransaction requireById(Long id) {
        return paymentTransactionRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Payment transaction not found"));
    }

    @Transactional
    public PaymentTransactionDto create(PaymentTransactionCreateRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request body is required");
        }

        PaymentTransaction saved;
        if (request.getPaymentIntentId() != null) {
            PaymentIntent paymentIntent = paymentIntentRepo.findById(request.getPaymentIntentId())
                    .orElseThrow(() -> new IllegalArgumentException("Payment intent not found"));
            saved = createOrReuseForIntent(request, paymentIntent, true);
        } else {
            saved = createManualTransaction(request);
        }

        return toDto(
                saved,
                zeroSafe(allocationRepo.sumAllocatedByPaymentTransactionId(saved.getId())),
                refundedAmountForSourcePaymentTransaction(saved.getId())
        );
    }

    @Transactional
    public PaymentTransaction ensureForPaidIntent(PaymentIntent paymentIntent) {
        if (paymentIntent == null || paymentIntent.getId() == null) {
            throw new IllegalArgumentException("paymentIntent is required");
        }
        return createOrReuseForIntent(new PaymentTransactionCreateRequest(), paymentIntent, false);
    }

    @Transactional(readOnly = true)
    public BigDecimal refundedAmountForSourcePaymentTransaction(Long sourcePaymentTransactionId) {
        if (sourcePaymentTransactionId == null) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal refunded = paymentTransactionRepo.findBySourcePaymentTransactionId(sourcePaymentTransactionId).stream()
                .filter(tx -> TRANSACTION_TYPE_REFUND.equals(normalizeTransactionType(tx.getTransactionType())))
                .filter(tx -> STATUS_POSTED.equals(normalizeStatus(tx.getStatus())))
                .map(PaymentTransaction::getAmount)
                .map(this::zeroSafe)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return money(refunded);
    }

    private PaymentTransaction createOrReuseForIntent(PaymentTransactionCreateRequest request,
                                                      PaymentIntent paymentIntent,
                                                      boolean enforceTenantScope) {
        if (!"PAID".equalsIgnoreCase(paymentIntent.getStatus())) {
            throw new IllegalStateException("Only PAID payment intents can be converted to payment transactions");
        }
        Optional<PaymentTransaction> existing = paymentTransactionRepo.findByPaymentIntentId(paymentIntent.getId());
        if (existing.isPresent()) {
            return enrichExistingTransactionWithCardType(existing.get(), request.getCardType(), paymentIntent);
        }

        Long tenantId = paymentIntent.getTenantId();
        if (enforceTenantScope) {
            Long requestedTenant = request.getTenantId() != null ? request.getTenantId() : paymentIntent.getTenantId();
            tenantId = TenantResolver.resolveTenantId(requestedTenant);
            if (!tenantId.equals(paymentIntent.getTenantId())) {
                throw new IllegalArgumentException("tenantId does not match payment intent tenant");
            }
        }

        Long reservationRequestId = resolveReservationRequestId(request.getReservationRequestId(), paymentIntent.getReservationRequestId());
        String paymentType = normalizePaymentType(request.getPaymentType(), PAYMENT_TYPE_CARD);
        String cardType = resolveCardType(request.getCardType(), paymentType, paymentIntent, tenantId);
        String status = normalizeStatus(request.getStatus());
        String currency = normalizeCurrency(request.getCurrency(), paymentIntent.getCurrency());
        BigDecimal amount = resolveIntentAmount(request.getAmount(), paymentIntent.getAmount());

        PaymentTransaction tx = PaymentTransaction.builder()
                .tenantId(tenantId)
                .reservationRequestId(reservationRequestId)
                .paymentIntentId(paymentIntent.getId())
                .transactionType(TRANSACTION_TYPE_CHARGE)
                .paymentType(paymentType)
                .cardType(cardType)
                .status(status)
                .currency(currency)
                .amount(amount)
                .refundType(null)
                .sourcePaymentTransactionId(null)
                .creditNoteInvoiceId(null)
                .externalRef(normalizeNullable(firstNonBlank(request.getExternalRef(), paymentIntent.getProviderPaymentId())))
                .note(normalizeNullable(request.getNote()))
                .build();
        return paymentTransactionRepo.save(tx);
    }

    private PaymentTransaction createManualTransaction(PaymentTransactionCreateRequest request) {
        Long tenantId = TenantResolver.resolveTenantId(request.getTenantId());
        String transactionType = normalizeTransactionType(request.getTransactionType());
        String paymentType = normalizePaymentType(request.getPaymentType(), null);
        String cardType = normalizeConfiguredCardType(request.getCardType(), paymentType, tenantId);
        String status = normalizeStatus(request.getStatus());
        String currency = normalizeCurrency(request.getCurrency(), null);
        BigDecimal amount = normalizeAmountForTransactionType(request.getAmount(), transactionType);
        String refundType = normalizeRefundType(request.getRefundType(), transactionType);
        Long sourcePaymentTransactionId = normalizeSourcePaymentTransactionId(
                request.getSourcePaymentTransactionId(), tenantId, transactionType
        );

        PaymentTransaction tx = PaymentTransaction.builder()
                .tenantId(tenantId)
                .reservationRequestId(request.getReservationRequestId())
                .paymentIntentId(null)
                .transactionType(transactionType)
                .paymentType(paymentType)
                .cardType(cardType)
                .status(status)
                .currency(currency)
                .amount(amount)
                .refundType(refundType)
                .sourcePaymentTransactionId(sourcePaymentTransactionId)
                .creditNoteInvoiceId(request.getCreditNoteInvoiceId())
                .externalRef(normalizeNullable(request.getExternalRef()))
                .note(normalizeNullable(request.getNote()))
                .build();
        return paymentTransactionRepo.save(tx);
    }

    private PaymentTransactionDto toDto(PaymentTransaction transaction, BigDecimal allocatedAmount, BigDecimal refundedAmount) {
        BigDecimal amount = money(zeroSafe(transaction.getAmount()));
        BigDecimal allocated = money(zeroSafe(allocatedAmount));
        BigDecimal refunded = money(zeroSafe(refundedAmount));
        BigDecimal available = availableAmount(transaction, allocated, refunded);
        return PaymentTransactionDto.builder()
                .id(transaction.getId())
                .tenantId(transaction.getTenantId())
                .reservationRequestId(transaction.getReservationRequestId())
                .paymentIntentId(transaction.getPaymentIntentId())
                .transactionType(transaction.getTransactionType())
                .paymentType(transaction.getPaymentType())
                .cardType(transaction.getCardType())
                .status(transaction.getStatus())
                .currency(transaction.getCurrency())
                .amount(amount)
                .allocatedAmount(allocated)
                .availableAmount(available)
                .refundType(transaction.getRefundType())
                .sourcePaymentTransactionId(transaction.getSourcePaymentTransactionId())
                .creditNoteInvoiceId(transaction.getCreditNoteInvoiceId())
                .externalRef(transaction.getExternalRef())
                .note(transaction.getNote())
                .createdAt(transaction.getCreatedAt())
                .build();
    }

    private Map<Long, BigDecimal> allocatedByTransactionId(List<Long> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        for (InvoicePaymentAllocationRepository.PaymentTransactionAllocationSum sum :
                allocationRepo.sumAllocatedByPaymentTransactionIds(transactionIds)) {
            result.put(sum.getPaymentTransactionId(), money(zeroSafe(sum.getTotalAllocated())));
        }
        return result;
    }

    private Map<Long, BigDecimal> refundedBySourceTransactionId(List<Long> transactionIds) {
        if (transactionIds == null || transactionIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new HashMap<>();
        for (Long transactionId : transactionIds) {
            result.put(transactionId, refundedAmountForSourcePaymentTransaction(transactionId));
        }
        return result;
    }

    private PaymentTransactionSearchCriteria normalizeAndValidate(PaymentTransactionSearchCriteria criteria) {
        PaymentTransactionSearchCriteria resolved = criteria != null ? criteria : new PaymentTransactionSearchCriteria();

        resolved.setTenantId(TenantResolver.resolveTenantId(resolved.getTenantId()));
        if (resolved.getReservationRequestId() == null) {
            if (resolved.getReservation_reqst() != null) {
                resolved.setReservationRequestId(resolved.getReservation_reqst());
            } else if (resolved.getReservation_request() != null) {
                resolved.setReservationRequestId(resolved.getReservation_request());
            }
        }

        resolved.setExternalRef(normalizeNullable(resolved.getExternalRef()));
        resolved.setNote(normalizeNullable(resolved.getNote()));
        resolved.setPaymentTypes(normalizePaymentTypes(resolved.getPaymentTypes()));
        resolved.setStatuses(normalizeStatuses(resolved.getStatuses()));
        resolved.setCurrencies(normalizeUpperValues(resolved.getCurrencies()));

        validateRange("createdAt", resolved.getCreatedFrom(), resolved.getCreatedTo());
        validateDecimalRange("amount", resolved.getAmountMin(), resolved.getAmountMax());

        return resolved;
    }

    private List<String> normalizePaymentTypes(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(v -> normalizePaymentType(v, null))
                .distinct()
                .toList();
    }

    private List<String> normalizeStatuses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .map(this::normalizeStatus)
                .distinct()
                .toList();
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

    private String normalizePaymentType(String paymentType, String defaultValue) {
        String raw = paymentType;
        if (!StringUtils.hasText(raw)) {
            raw = defaultValue;
        }
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("paymentType is required");
        }

        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("BANK".equals(normalized)) {
            normalized = PAYMENT_TYPE_BANK_TRANSFER;
        }
        if (!SUPPORTED_PAYMENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("paymentType must be CASH, CARD, BANK_TRANSFER, or ROOM_CHARGE");
        }
        return normalized;
    }

    private PaymentTransaction enrichExistingTransactionWithCardType(PaymentTransaction transaction,
                                                                     String requestedCardType,
                                                                     PaymentIntent paymentIntent) {
        if (transaction == null || !PAYMENT_TYPE_CARD.equals(transaction.getPaymentType())) {
            return transaction;
        }
        if (StringUtils.hasText(transaction.getCardType())) {
            return transaction;
        }
        String resolvedCardType = resolveCardType(requestedCardType, transaction.getPaymentType(), paymentIntent, transaction.getTenantId());
        if (!StringUtils.hasText(resolvedCardType)) {
            return transaction;
        }
        transaction.setCardType(resolvedCardType);
        return paymentTransactionRepo.save(transaction);
    }

    private String resolveCardType(String requestedCardType,
                                   String paymentType,
                                   PaymentIntent paymentIntent,
                                   Long tenantId) {
        String normalizedRequested = normalizeConfiguredCardType(requestedCardType, paymentType, tenantId);
        if (normalizedRequested != null) {
            return normalizedRequested;
        }
        if (!PAYMENT_TYPE_CARD.equals(paymentType) || paymentIntent == null || paymentIntent.getId() == null) {
            return null;
        }
        return paymentEventRepo.findByPaymentIntentIdOrderByCreatedAtDesc(paymentIntent.getId()).stream()
                .map(PaymentEvent::getPayload)
                .map(this::extractCardTypeFromPayload)
                .filter(StringUtils::hasText)
                .map(cardType -> paymentCardTypeService.findActiveCodeOrNull(tenantId, cardType))
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
    }

    private String normalizeConfiguredCardType(String cardType, String paymentType, Long tenantId) {
        String normalizedCardType = normalizeCardType(cardType, paymentType);
        if (normalizedCardType == null) {
            return null;
        }
        return paymentCardTypeService.requireActiveCode(tenantId, normalizedCardType);
    }

    private String normalizeCardType(String cardType, String paymentType) {
        if (!StringUtils.hasText(cardType) || !PAYMENT_TYPE_CARD.equals(paymentType)) {
            return null;
        }
        return cardType.trim().toUpperCase(Locale.ROOT);
    }

    private String extractCardTypeFromPayload(JsonNode payload) {
        return firstNonBlank(
                textAt(payload, "payload.payment_method.card.brand"),
                firstNonBlank(
                        textAt(payload, "payload.payment_method.brand"),
                        firstNonBlank(
                                textAt(payload, "payload.card.brand"),
                                firstNonBlank(
                                        textAt(payload, "payment_method.card.brand"),
                                        firstNonBlank(
                                                textAt(payload, "payment_method.brand"),
                                                firstNonBlank(
                                                        textAt(payload, "card.brand"),
                                                        textAt(payload, "brand")
                                                )
                                        )
                                )
                        )
                )
        );
    }

    private String textAt(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        JsonNode current = root;
        for (String part : path.split("\\.")) {
            if (current == null) {
                return null;
            }
            current = current.path(part);
        }
        if (current == null || current.isMissingNode() || current.isNull()) {
            return null;
        }
        String value = current.asText();
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : null;
    }

    private String normalizeStatus(String status) {
        String raw = StringUtils.hasText(status)
                ? status.trim().toUpperCase(Locale.ROOT)
                : STATUS_POSTED;

        // Frontend flows can send invoice/payment lifecycle statuses; map them to payment transaction statuses.
        String normalized = switch (raw) {
            case "VOID", "CANCELLED", "CANCELED" -> "VOIDED";
            case "DRAFT", "ISSUED", "UNPAID", "PARTIALLY_PAID", "PAID",
                    "PENDING", "PENDING_PAYMENT", "PROCESSING",
                    "SUCCESS", "SUCCEEDED", "COMPLETED", "AUTHORIZED", "AUTHORISED" -> STATUS_POSTED;
            default -> raw;
        };

        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be POSTED or VOIDED (received: " + raw + ")");
        }
        return normalized;
    }

    private String normalizeTransactionType(String transactionType) {
        String raw = StringUtils.hasText(transactionType)
                ? transactionType.trim().toUpperCase(Locale.ROOT)
                : TRANSACTION_TYPE_CHARGE;
        if (!SUPPORTED_TRANSACTION_TYPES.contains(raw)) {
            throw new IllegalArgumentException("transactionType must be CHARGE or REFUND");
        }
        return raw;
    }

    private String normalizeRefundType(String refundType, String transactionType) {
        if (!TRANSACTION_TYPE_REFUND.equals(transactionType)) {
            if (StringUtils.hasText(refundType)) {
                throw new IllegalArgumentException("refundType is supported only for REFUND transactions");
            }
            return null;
        }
        if (!StringUtils.hasText(refundType)) {
            return null;
        }
        String normalized = refundType.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_REFUND_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("refundType must be CANCELLATION or MANUAL");
        }
        return normalized;
    }

    private String normalizeCurrency(String currency, String fallback) {
        String raw = StringUtils.hasText(currency) ? currency : fallback;
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("currency is required");
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private BigDecimal resolveIntentAmount(BigDecimal requestedAmount, BigDecimal intentAmount) {
        BigDecimal normalizedIntentAmount = requirePositiveAmount(intentAmount);
        if (requestedAmount == null) {
            return normalizedIntentAmount;
        }
        BigDecimal normalizedRequestedAmount = requirePositiveAmount(requestedAmount);
        if (normalizedRequestedAmount.compareTo(normalizedIntentAmount) != 0) {
            throw new IllegalArgumentException("amount must match paid payment intent amount");
        }
        return normalizedRequestedAmount;
    }

    private BigDecimal requirePositiveAmount(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("amount must be greater than zero");
        }
        return money(value);
    }

    private BigDecimal requireNegativeAmount(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) >= 0) {
            throw new IllegalArgumentException("amount must be less than zero");
        }
        return money(value);
    }

    private BigDecimal normalizeAmountForTransactionType(BigDecimal amount, String transactionType) {
        if (TRANSACTION_TYPE_REFUND.equals(transactionType)) {
            return requireNegativeAmount(amount);
        }
        return requirePositiveAmount(amount);
    }

    private Long normalizeSourcePaymentTransactionId(Long sourcePaymentTransactionId, Long tenantId, String transactionType) {
        if (!TRANSACTION_TYPE_REFUND.equals(transactionType)) {
            if (sourcePaymentTransactionId != null) {
                throw new IllegalArgumentException("sourcePaymentTransactionId is supported only for REFUND transactions");
            }
            return null;
        }
        if (sourcePaymentTransactionId == null) {
            return null;
        }
        PaymentTransaction sourceTransaction = paymentTransactionRepo.findById(sourcePaymentTransactionId)
                .orElseThrow(() -> new IllegalArgumentException("Source payment transaction not found"));
        if (!tenantId.equals(sourceTransaction.getTenantId())) {
            throw new IllegalArgumentException("sourcePaymentTransactionId tenant mismatch");
        }
        if (!TRANSACTION_TYPE_CHARGE.equals(normalizeTransactionType(sourceTransaction.getTransactionType()))) {
            throw new IllegalArgumentException("Refund source payment transaction must be a CHARGE transaction");
        }
        return sourcePaymentTransactionId;
    }

    private String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StringUtils.hasText(primary)) {
            return primary;
        }
        return fallback;
    }

    private Long resolveReservationRequestId(Long requestId, Long fallbackRequestId) {
        if (requestId != null && fallbackRequestId != null && !requestId.equals(fallbackRequestId)) {
            throw new IllegalArgumentException("reservationRequestId does not match payment intent");
        }
        return requestId != null ? requestId : fallbackRequestId;
    }

    private BigDecimal zeroSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private BigDecimal money(BigDecimal value) {
        return zeroSafe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal availableAmount(PaymentTransaction transaction, BigDecimal allocatedAmount, BigDecimal refundedAmount) {
        String transactionType = normalizeTransactionType(transaction.getTransactionType());
        if (TRANSACTION_TYPE_REFUND.equals(transactionType)) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return money(zeroSafe(transaction.getAmount())
                .subtract(zeroSafe(allocatedAmount))
                .subtract(zeroSafe(refundedAmount)));
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
