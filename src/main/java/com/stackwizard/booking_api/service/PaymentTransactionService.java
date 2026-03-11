package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.dto.PaymentTransactionDto;
import com.stackwizard.booking_api.dto.PaymentTransactionSearchCriteria;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.repository.InvoicePaymentAllocationRepository;
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

    private final PaymentTransactionRepository paymentTransactionRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final InvoicePaymentAllocationRepository allocationRepo;

    public PaymentTransactionService(PaymentTransactionRepository paymentTransactionRepo,
                                     PaymentIntentRepository paymentIntentRepo,
                                     InvoicePaymentAllocationRepository allocationRepo) {
        this.paymentTransactionRepo = paymentTransactionRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.allocationRepo = allocationRepo;
    }

    @Transactional(readOnly = true)
    public Page<PaymentTransactionDto> search(PaymentTransactionSearchCriteria criteria, Pageable pageable) {
        PaymentTransactionSearchCriteria normalized = normalizeAndValidate(criteria);
        Page<PaymentTransaction> page = paymentTransactionRepo.findAll(PaymentTransactionSpecifications.byCriteria(normalized), pageable);
        List<Long> transactionIds = page.getContent().stream().map(PaymentTransaction::getId).toList();
        Map<Long, BigDecimal> allocatedByTransactionId = allocatedByTransactionId(transactionIds);
        return page.map(tx -> toDto(tx, allocatedByTransactionId.getOrDefault(tx.getId(), BigDecimal.ZERO)));
    }

    @Transactional(readOnly = true)
    public Optional<PaymentTransactionDto> findById(Long id) {
        return paymentTransactionRepo.findById(id)
                .map(tx -> toDto(tx, zeroSafe(allocationRepo.sumAllocatedByPaymentTransactionId(tx.getId()))));
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

        return toDto(saved, zeroSafe(allocationRepo.sumAllocatedByPaymentTransactionId(saved.getId())));
    }

    @Transactional
    public PaymentTransaction ensureForPaidIntent(PaymentIntent paymentIntent) {
        if (paymentIntent == null || paymentIntent.getId() == null) {
            throw new IllegalArgumentException("paymentIntent is required");
        }
        return createOrReuseForIntent(new PaymentTransactionCreateRequest(), paymentIntent, false);
    }

    private PaymentTransaction createOrReuseForIntent(PaymentTransactionCreateRequest request,
                                                      PaymentIntent paymentIntent,
                                                      boolean enforceTenantScope) {
        if (!"PAID".equalsIgnoreCase(paymentIntent.getStatus())) {
            throw new IllegalStateException("Only PAID payment intents can be converted to payment transactions");
        }
        Optional<PaymentTransaction> existing = paymentTransactionRepo.findByPaymentIntentId(paymentIntent.getId());
        if (existing.isPresent()) {
            return existing.get();
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
        String status = normalizeStatus(request.getStatus());
        String currency = normalizeCurrency(request.getCurrency(), paymentIntent.getCurrency());
        BigDecimal amount = resolveIntentAmount(request.getAmount(), paymentIntent.getAmount());

        PaymentTransaction tx = PaymentTransaction.builder()
                .tenantId(tenantId)
                .reservationRequestId(reservationRequestId)
                .paymentIntentId(paymentIntent.getId())
                .paymentType(paymentType)
                .status(status)
                .currency(currency)
                .amount(amount)
                .externalRef(normalizeNullable(firstNonBlank(request.getExternalRef(), paymentIntent.getProviderPaymentId())))
                .note(normalizeNullable(request.getNote()))
                .build();
        return paymentTransactionRepo.save(tx);
    }

    private PaymentTransaction createManualTransaction(PaymentTransactionCreateRequest request) {
        Long tenantId = TenantResolver.resolveTenantId(request.getTenantId());
        String paymentType = normalizePaymentType(request.getPaymentType(), null);
        String status = normalizeStatus(request.getStatus());
        String currency = normalizeCurrency(request.getCurrency(), null);
        BigDecimal amount = requirePositiveAmount(request.getAmount());

        PaymentTransaction tx = PaymentTransaction.builder()
                .tenantId(tenantId)
                .reservationRequestId(request.getReservationRequestId())
                .paymentIntentId(null)
                .paymentType(paymentType)
                .status(status)
                .currency(currency)
                .amount(amount)
                .externalRef(normalizeNullable(request.getExternalRef()))
                .note(normalizeNullable(request.getNote()))
                .build();
        return paymentTransactionRepo.save(tx);
    }

    private PaymentTransactionDto toDto(PaymentTransaction transaction, BigDecimal allocatedAmount) {
        BigDecimal amount = money(zeroSafe(transaction.getAmount()));
        BigDecimal allocated = money(zeroSafe(allocatedAmount));
        return PaymentTransactionDto.builder()
                .id(transaction.getId())
                .tenantId(transaction.getTenantId())
                .reservationRequestId(transaction.getReservationRequestId())
                .paymentIntentId(transaction.getPaymentIntentId())
                .paymentType(transaction.getPaymentType())
                .status(transaction.getStatus())
                .currency(transaction.getCurrency())
                .amount(amount)
                .allocatedAmount(allocated)
                .availableAmount(money(amount.subtract(allocated)))
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

    private String normalizeStatus(String status) {
        String normalized = StringUtils.hasText(status)
                ? status.trim().toUpperCase(Locale.ROOT)
                : STATUS_POSTED;
        if (!SUPPORTED_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("status must be POSTED or VOIDED");
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
