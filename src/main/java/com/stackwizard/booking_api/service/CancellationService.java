package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.dto.CancellationExecuteRequest;
import com.stackwizard.booking_api.dto.CancellationRequestDto;
import com.stackwizard.booking_api.dto.PaymentTransactionCreateRequest;
import com.stackwizard.booking_api.dto.PublicCancellationPreviewDto;
import com.stackwizard.booking_api.model.Allocation;
import com.stackwizard.booking_api.model.CancellationRequest;
import com.stackwizard.booking_api.model.Invoice;
import com.stackwizard.booking_api.model.InvoicePaymentAllocation;
import com.stackwizard.booking_api.model.PaymentIntent;
import com.stackwizard.booking_api.model.PaymentTransaction;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.model.ReservationRequestAccessToken;
import com.stackwizard.booking_api.repository.AllocationRepository;
import com.stackwizard.booking_api.repository.CancellationRequestRepository;
import com.stackwizard.booking_api.repository.PaymentIntentRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.service.fiscal.InvoiceAutoFiscalizationRequestedEvent;
import org.springframework.context.ApplicationEventPublisher;
import com.stackwizard.booking_api.service.payment.PaymentProviderClient;
import com.stackwizard.booking_api.service.payment.PaymentProviderRefundRequest;
import com.stackwizard.booking_api.service.payment.PaymentProviderRefundResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class CancellationService {
    private static final Logger log = LoggerFactory.getLogger(CancellationService.class);
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_COMPLETED = "COMPLETED";
    private static final String STATUS_FAILED = "FAILED";
    private static final String MODE_CASH_REFUND = "CASH_REFUND";
    private static final String MODE_CUSTOMER_CREDIT = "CUSTOMER_CREDIT";
    private static final String MODE_NONE = "NONE";

    private final CancellationRequestRepository cancellationRequestRepo;
    private final ReservationRequestRepository requestRepo;
    private final ReservationRepository reservationRepo;
    private final AllocationRepository allocationRepo;
    private final PaymentIntentRepository paymentIntentRepo;
    private final InvoiceService invoiceService;
    private final PaymentTransactionService paymentTransactionService;
    private final CancellationPolicyService cancellationPolicyService;
    private final ReservationRequestAccessTokenService accessTokenService;
    private final ApplicationEventPublisher eventPublisher;
    private final Map<String, PaymentProviderClient> providerClients;

    public CancellationService(CancellationRequestRepository cancellationRequestRepo,
                               ReservationRequestRepository requestRepo,
                               ReservationRepository reservationRepo,
                               AllocationRepository allocationRepo,
                               PaymentIntentRepository paymentIntentRepo,
                               InvoiceService invoiceService,
                               PaymentTransactionService paymentTransactionService,
                               CancellationPolicyService cancellationPolicyService,
                               ReservationRequestAccessTokenService accessTokenService,
                               ApplicationEventPublisher eventPublisher,
                               List<PaymentProviderClient> providerClients) {
        this.cancellationRequestRepo = cancellationRequestRepo;
        this.requestRepo = requestRepo;
        this.reservationRepo = reservationRepo;
        this.allocationRepo = allocationRepo;
        this.paymentIntentRepo = paymentIntentRepo;
        this.invoiceService = invoiceService;
        this.paymentTransactionService = paymentTransactionService;
        this.cancellationPolicyService = cancellationPolicyService;
        this.accessTokenService = accessTokenService;
        this.eventPublisher = eventPublisher;
        this.providerClients = providerClients.stream()
                .collect(Collectors.toMap(c -> c.providerCode().toUpperCase(Locale.ROOT), c -> c));
    }

    @Transactional(readOnly = true)
    public List<CancellationRequestDto> findByReservationRequestId(Long reservationRequestId) {
        return cancellationRequestRepo.findByReservationRequestIdOrderByCreatedAtDescIdDesc(reservationRequestId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public CancellationRequestDto execute(Long reservationRequestId, CancellationExecuteRequest request) {
        return execute(reservationRequestId, request, true);
    }

    @Transactional
    public CancellationRequestDto executePublic(Long reservationRequestId, CancellationExecuteRequest request) {
        CancellationExecuteRequest sanitized = new CancellationExecuteRequest();
        sanitized.setNote(request != null ? request.getNote() : null);
        return execute(reservationRequestId, sanitized, false);
    }

    @Transactional(readOnly = true)
    public PublicCancellationPreviewDto previewPublic(Long reservationRequestId) {
        ReservationRequest reservationRequest = requestRepo.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));
        Optional<CancellationRequest> latestExisting = cancellationRequestRepo
                .findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(reservationRequestId);
        if (reservationRequest.getStatus() == ReservationRequest.Status.CANCELLED) {
            CancellationRequest completed = latestExisting
                    .filter(existing -> STATUS_COMPLETED.equals(existing.getStatus()))
                    .orElse(null);
            return PublicCancellationPreviewDto.builder()
                    .canCancel(false)
                    .status("ALREADY_CANCELLED")
                    .settlementMode(completed != null ? completed.getSettlementMode() : null)
                    .currency(completed != null ? completed.getCurrency() : null)
                    .cancelledAmount(completed != null ? completed.getCancelledAmount() : amountZero())
                    .releasedAmount(completed != null ? completed.getReleasedAmount() : amountZero())
                    .refundAmount(completed != null ? completed.getRefundAmount() : amountZero())
                    .penaltyAmount(completed != null ? completed.getPenaltyAmount() : amountZero())
                    .creditAmount(completed != null ? completed.getCreditAmount() : amountZero())
                    .policyText(reservationRequest.getCancellationPolicyText())
                    .message("This reservation has already been cancelled.")
                    .build();
        }
        if (reservationRequest.getStatus() != ReservationRequest.Status.FINALIZED) {
            return unavailablePreview(reservationRequest, "Reservation is not eligible for online cancellation.");
        }

        List<Reservation> reservations = activeReservationsOnly(reservationRepo.findByRequestId(reservationRequestId));
        if (reservations.isEmpty()) {
            return unavailablePreview(reservationRequest, "No active booked items to cancel (lines may already be cancelled).");
        }

        try {
            Invoice sourceInvoice = invoiceService.findCancellationSourceInvoiceByRequestId(reservationRequestId).orElse(null);
            PaymentTransaction sourceCharge = sourceInvoice != null ? resolveSourceChargeTransaction(sourceInvoice) : null;
            String currency = resolveCurrency(reservations, sourceInvoice, sourceCharge);
            BigDecimal cancelledAmount = money(reservations.stream()
                    .map(Reservation::getGrossAmount)
                    .map(this::zeroSafe)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));

            List<CancellationPolicyService.ReservationCancellationEvaluation> evaluations = reservations.stream()
                    .map(reservation -> cancellationPolicyService.evaluateReservation(reservation, null))
                    .toList();
            BigDecimal releasedByPolicy = money(evaluations.stream()
                    .map(CancellationPolicyService.ReservationCancellationEvaluation::releasedAmount)
                    .map(this::zeroSafe)
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
            String settlementMode = resolveOverallSettlementMode(evaluations, null);
            BigDecimal paidBaseAmount = sourceInvoice != null
                    ? money(abs(sourceInvoice.getTotalGross()))
                    : sourceCharge != null
                    ? money(abs(sourceCharge.getAmount()))
                    : amountZero();
            BigDecimal releasedAmount = MODE_NONE.equals(settlementMode)
                    ? amountZero()
                    : money(min(paidBaseAmount, releasedByPolicy));
            BigDecimal refundAmount = MODE_CASH_REFUND.equals(settlementMode) ? releasedAmount : amountZero();
            BigDecimal creditAmount = MODE_CUSTOMER_CREDIT.equals(settlementMode) ? releasedAmount : amountZero();
            BigDecimal penaltyAmount = MODE_NONE.equals(settlementMode)
                    ? paidBaseAmount
                    : money(paidBaseAmount.subtract(releasedAmount).max(BigDecimal.ZERO));
            LocalDateTime freeCancellationUntil = evaluations.stream()
                    .map(CancellationPolicyService.ReservationCancellationEvaluation::cutoffAt)
                    .filter(value -> value != null)
                    .min(LocalDateTime::compareTo)
                    .orElse(null);

            return PublicCancellationPreviewDto.builder()
                    .canCancel(true)
                    .status("AVAILABLE")
                    .settlementMode(settlementMode)
                    .currency(currency)
                    .cancelledAmount(cancelledAmount)
                    .releasedAmount(releasedAmount)
                    .refundAmount(refundAmount)
                    .penaltyAmount(penaltyAmount)
                    .creditAmount(creditAmount)
                    .freeCancellationUntil(freeCancellationUntil)
                    .policyText(reservationRequest.getCancellationPolicyText())
                    .message(buildPublicPreviewMessage(settlementMode, refundAmount, creditAmount, penaltyAmount, currency))
                    .build();
        } catch (RuntimeException ex) {
            return unavailablePreview(reservationRequest, ex.getMessage());
        }
    }

    private CancellationRequestDto execute(Long reservationRequestId,
                                           CancellationExecuteRequest request,
                                           boolean allowSettlementOverride) {
        ReservationRequest reservationRequest = requestRepo.findById(reservationRequestId)
                .orElseThrow(() -> new IllegalArgumentException("Reservation request not found"));

        Optional<CancellationRequest> latestExisting = cancellationRequestRepo
                .findFirstByReservationRequestIdOrderByCreatedAtDescIdDesc(reservationRequestId);
        if (reservationRequest.getStatus() == ReservationRequest.Status.CANCELLED
                && latestExisting.filter(existing -> STATUS_COMPLETED.equals(existing.getStatus())).isPresent()) {
            return toDto(latestExisting.get());
        }
        if (reservationRequest.getStatus() != ReservationRequest.Status.FINALIZED) {
            throw new IllegalStateException("Only FINALIZED reservation requests can be cancelled");
        }

        List<Reservation> reservations = activeReservationsOnly(reservationRepo.findByRequestId(reservationRequestId));
        if (reservations.isEmpty()) {
            throw new IllegalStateException(
                    "No active reservations to cancel; all lines are already cancelled (e.g. after amendments).");
        }

        Invoice sourceInvoice = invoiceService.findCancellationSourceInvoiceByRequestId(reservationRequestId).orElse(null);
        PaymentTransaction sourceCharge = sourceInvoice != null ? resolveSourceChargeTransaction(sourceInvoice) : null;
        String currency = resolveCurrency(reservations, sourceInvoice, sourceCharge);
        BigDecimal cancelledAmount = money(reservations.stream()
                .map(Reservation::getGrossAmount)
                .map(this::zeroSafe)
                .reduce(BigDecimal.ZERO, BigDecimal::add));

        BigDecimal paidBaseAmount = sourceInvoice != null
                ? money(abs(sourceInvoice.getTotalGross()))
                : sourceCharge != null
                ? money(abs(sourceCharge.getAmount()))
                : BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);

        String requestedSettlementMode = request != null ? normalizeSettlementMode(request.getSettlementMode()) : null;
        String policyEvaluationSettlementMode = allowSettlementOverride ? null : requestedSettlementMode;

        List<CancellationPolicyService.ReservationCancellationEvaluation> evaluations = reservations.stream()
                .map(reservation -> cancellationPolicyService.evaluateReservation(reservation, policyEvaluationSettlementMode))
                .toList();

        BigDecimal releasedByPolicy = money(evaluations.stream()
                .map(CancellationPolicyService.ReservationCancellationEvaluation::releasedAmount)
                .map(this::zeroSafe)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        // Admin cancellation: when policy releases 0 (e.g. after cutoff, NONE release) but the guest still paid a
        // deposit, allow refund/credit up to min(paid, active booking gross). Deposit-only lines (gross sum 0)
        // still use the paid deposit as the cap.
        if (allowSettlementOverride
                && (MODE_CASH_REFUND.equals(requestedSettlementMode) || MODE_CUSTOMER_CREDIT.equals(requestedSettlementMode))
                && releasedByPolicy.compareTo(BigDecimal.ZERO) <= 0
                && paidBaseAmount.compareTo(BigDecimal.ZERO) > 0) {
            if (cancelledAmount.compareTo(BigDecimal.ZERO) <= 0) {
                releasedByPolicy = paidBaseAmount;
            } else {
                releasedByPolicy = money(min(paidBaseAmount, cancelledAmount));
            }
        }
        String settlementMode = requestedSettlementMode != null
                ? requestedSettlementMode
                : resolveOverallSettlementMode(evaluations, null);
        BigDecimal releasedAmount = MODE_NONE.equals(settlementMode)
                ? BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)
                : money(min(paidBaseAmount, releasedByPolicy));
        if (MODE_NONE.equals(settlementMode) && releasedByPolicy.compareTo(BigDecimal.ZERO) > 0 && !allowSettlementOverride) {
            throw new IllegalArgumentException("Settlement mode NONE is not allowed when cancellation policy releases value");
        }
        if (!MODE_NONE.equals(settlementMode) && releasedByPolicy.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "Refund or credit requires a non-zero amount released by the cancellation policy "
                            + "(released total is 0 at current cutoff/rules). "
                            + "Use settlement mode NONE for a penalty-only cancellation, or check policy and reservation amounts.");
        }

        BigDecimal refundAmount = MODE_CASH_REFUND.equals(settlementMode) ? releasedAmount : amountZero();
        BigDecimal creditAmount = MODE_CUSTOMER_CREDIT.equals(settlementMode) ? releasedAmount : amountZero();
        BigDecimal penaltyAmount = MODE_NONE.equals(settlementMode)
                ? paidBaseAmount
                : money(paidBaseAmount.subtract(releasedAmount).max(BigDecimal.ZERO));

        CancellationRequest cancellationRequest = cancellationRequestRepo.save(CancellationRequest.builder()
                .tenantId(reservationRequest.getTenantId())
                .reservationRequestId(reservationRequestId)
                .status(STATUS_PENDING)
                .settlementMode(settlementMode)
                .currency(currency)
                .sourceInvoiceId(sourceInvoice != null ? sourceInvoice.getId() : null)
                .sourcePaymentTransactionId(sourceCharge != null ? sourceCharge.getId() : null)
                .cancelledAmount(cancelledAmount)
                .releasedAmount(releasedAmount)
                .refundAmount(refundAmount)
                .penaltyAmount(penaltyAmount)
                .creditAmount(creditAmount)
                .reservationRequestUrl(resolveReservationRequestUrl(reservationRequestId))
                .note(request != null ? normalizeNullable(request.getNote()) : null)
                .build());

        try {
            if (MODE_CASH_REFUND.equals(settlementMode)) {
                if (sourceInvoice == null || sourceCharge == null) {
                    throw new IllegalStateException("Cash refund requires source invoice and charge transaction");
                }
                Invoice creditNote = invoiceService.createCreditNoteInvoice(sourceInvoice.getId());
                cancellationRequest.setCreditNoteInvoiceId(creditNote.getId());

                boolean automaticRefund = request == null
                        || request.getAutomaticRefund() == null
                        || Boolean.TRUE.equals(request.getAutomaticRefund());
                PaymentProviderRefundResult refundResult = null;
                if (automaticRefund && sourceCharge.getPaymentIntentId() != null) {
                    refundResult = refundSourcePayment(
                            reservationRequest,
                            sourceCharge,
                            refundAmount,
                            currency,
                            cancellationRequest.getNote()
                    );
                } else if (automaticRefund) {
                    log.info(
                            "Skipping provider refund for reservation request {} because source payment transaction {} is not linked to a payment intent",
                            reservationRequestId,
                            sourceCharge.getId()
                    );
                } else {
                    log.info(
                            "Skipping provider refund for reservation request {} because automaticRefund is disabled",
                            reservationRequestId
                    );
                }

                PaymentTransaction refundTransaction = createRefundTransaction(
                        reservationRequest,
                        sourceCharge,
                        creditNote,
                        refundAmount,
                        refundResult,
                        automaticRefund
                                ? "Cancellation refund handled manually (no provider payment intent link)"
                                : "Cancellation refund handled manually by finance"
                );
                invoiceService.allocatePaymentToInvoice(
                        creditNote.getId(),
                        refundTransaction.getId(),
                        refundTransaction.getAmount(),
                        "REFUND_RELEASE"
                );
                cancellationRequest.setRefundPaymentTransactionId(refundTransaction.getId());
                publishAutoFiscalizationIfRequired(creditNote);
            } else if (MODE_CUSTOMER_CREDIT.equals(settlementMode)) {
                if (sourceInvoice != null) {
                    Invoice stornoInvoice = invoiceService.createStornoInvoice(sourceInvoice.getId());
                    cancellationRequest.setStornoInvoiceId(stornoInvoice.getId());
                    publishAutoFiscalizationIfRequired(stornoInvoice);
                }
                if (penaltyAmount.compareTo(BigDecimal.ZERO) > 0) {
                    if (sourceCharge == null) {
                        throw new IllegalStateException("Penalty allocation requires source charge transaction");
                    }
                    Invoice penaltyInvoice = invoiceService.createPenaltyInvoice(reservationRequestId, penaltyAmount, currency);
                    cancellationRequest.setPenaltyInvoiceId(penaltyInvoice.getId());
                    publishAutoFiscalizationIfRequired(penaltyInvoice);
                    invoiceService.allocatePaymentToInvoice(penaltyInvoice.getId(), sourceCharge.getId(), penaltyAmount, "SETTLEMENT");
                }
            } else {
                if (sourceInvoice != null) {
                    Invoice stornoInvoice = invoiceService.createStornoInvoice(sourceInvoice.getId());
                    cancellationRequest.setStornoInvoiceId(stornoInvoice.getId());
                    publishAutoFiscalizationIfRequired(stornoInvoice);
                }
                // Admin no-refund cancellations keep only the paid deposit/base amount as penalty.
                // We intentionally do not create a full final invoice from reservation products here.
                if (penaltyAmount.compareTo(BigDecimal.ZERO) > 0) {
                    Invoice penaltyInvoice = invoiceService.createPenaltyInvoice(reservationRequestId, penaltyAmount, currency);
                    cancellationRequest.setPenaltyInvoiceId(penaltyInvoice.getId());
                    publishAutoFiscalizationIfRequired(penaltyInvoice);
                    if (sourceCharge != null) {
                        invoiceService.allocatePaymentToInvoice(
                                penaltyInvoice.getId(),
                                sourceCharge.getId(),
                                penaltyAmount,
                                "SETTLEMENT"
                        );
                    }
                }
            }

            markRequestCancelled(reservationRequest, reservations);
            cancellationRequest.setStatus(STATUS_COMPLETED);
            cancellationRequest.setCompletedAt(OffsetDateTime.now());
            cancellationRequest.setFailureReason(null);
            cancellationRequest = cancellationRequestRepo.save(cancellationRequest);
            eventPublisher.publishEvent(new ReservationRequestCancelledEvent(reservationRequestId, cancellationRequest.getId()));
            return toDto(cancellationRequest);
        } catch (RuntimeException ex) {
            cancellationRequest.setStatus(STATUS_FAILED);
            cancellationRequest.setFailureReason(ex.getMessage());
            cancellationRequestRepo.save(cancellationRequest);
            throw ex;
        }
    }

    private PaymentProviderRefundResult refundSourcePayment(ReservationRequest reservationRequest,
                                                            PaymentTransaction sourceCharge,
                                                            BigDecimal refundAmount,
                                                            String currency,
                                                            String note) {
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("refundAmount must be greater than zero");
        }
        if (sourceCharge.getPaymentIntentId() == null) {
            throw new IllegalStateException("Source payment transaction is not linked to a payment intent");
        }
        PaymentIntent paymentIntent = paymentIntentRepo.findById(sourceCharge.getPaymentIntentId())
                .orElseThrow(() -> new IllegalArgumentException("Payment intent not found for source payment"));
        PaymentProviderClient providerClient = providerClients.get(
                paymentIntent.getProvider() != null ? paymentIntent.getProvider().trim().toUpperCase(Locale.ROOT) : ""
        );
        if (providerClient == null) {
            throw new IllegalStateException("Payment provider is not configured for refund");
        }
        return providerClient.refund(new PaymentProviderRefundRequest(
                reservationRequest.getTenantId(),
                paymentIntent.getProviderPaymentId(),
                paymentIntent.getProviderOrderNumber(),
                refundAmount,
                currency,
                firstNonBlank(note, "Cancellation refund for reservation request #" + reservationRequest.getId())
        ));
    }

    private PaymentTransaction createRefundTransaction(ReservationRequest reservationRequest,
                                                       PaymentTransaction sourceCharge,
                                                       Invoice creditNote,
                                                       BigDecimal refundAmount,
                                                       PaymentProviderRefundResult refundResult,
                                                       String fallbackNote) {
        PaymentTransactionCreateRequest request = new PaymentTransactionCreateRequest();
        request.setTenantId(reservationRequest.getTenantId());
        request.setReservationRequestId(reservationRequest.getId());
        request.setTransactionType("REFUND");
        request.setPaymentType(sourceCharge.getPaymentType());
        request.setCardType(sourceCharge.getCardType());
        request.setStatus("POSTED");
        request.setCurrency(sourceCharge.getCurrency());
        request.setAmount(refundAmount.negate());
        request.setRefundType("CANCELLATION");
        request.setSourcePaymentTransactionId(sourceCharge.getId());
        request.setCreditNoteInvoiceId(creditNote.getId());
        String externalRef = refundResult != null
                ? firstNonBlank(refundResult.externalRef(), refundResult.providerRefundId())
                : null;
        request.setExternalRef(externalRef);
        request.setNote(refundResult != null ? "Cancellation refund" : fallbackNote);
        return paymentTransactionService.requireById(paymentTransactionService.create(request).getId());
    }

    private PaymentTransaction resolveSourceChargeTransaction(Invoice sourceInvoice) {
        List<InvoicePaymentAllocation> allocations = invoiceService.findAllocations(sourceInvoice.getId()).stream()
                .filter(allocation -> allocation.getAllocatedAmount() != null && allocation.getAllocatedAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(allocation -> allocation.getPaymentTransaction() != null)
                .filter(allocation -> "CHARGE".equalsIgnoreCase(allocation.getPaymentTransaction().getTransactionType()))
                .toList();
        if (allocations.isEmpty()) {
            return null;
        }
        Set<Long> transactionIds = allocations.stream()
                .map(allocation -> allocation.getPaymentTransaction().getId())
                .collect(Collectors.toSet());
        if (transactionIds.size() > 1) {
            throw new IllegalStateException("Cancellation currently supports a single source payment transaction");
        }
        return allocations.getFirst().getPaymentTransaction();
    }

    private String resolveCurrency(List<Reservation> reservations, Invoice sourceInvoice, PaymentTransaction sourceCharge) {
        if (sourceCharge != null && StringUtils.hasText(sourceCharge.getCurrency())) {
            return sourceCharge.getCurrency();
        }
        if (sourceInvoice != null && StringUtils.hasText(sourceInvoice.getCurrency())) {
            return sourceInvoice.getCurrency();
        }
        return reservations.stream()
                .map(Reservation::getCurrency)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("EUR");
    }

    private String resolveOverallSettlementMode(List<CancellationPolicyService.ReservationCancellationEvaluation> evaluations,
                                                String requestedSettlementMode) {
        String normalizedRequested = normalizeSettlementMode(requestedSettlementMode);
        if (normalizedRequested != null) {
            return normalizedRequested;
        }
        List<String> resolvedModes = evaluations.stream()
                .filter(evaluation -> evaluation.releasedAmount().compareTo(BigDecimal.ZERO) > 0)
                .map(CancellationPolicyService.ReservationCancellationEvaluation::settlementMode)
                .distinct()
                .toList();
        if (resolvedModes.isEmpty()) {
            return MODE_NONE;
        }
        if (resolvedModes.size() > 1) {
            throw new IllegalArgumentException("Cancellation request contains reservations with different default settlement modes; specify settlementMode");
        }
        return resolvedModes.getFirst();
    }

    /**
     * Amendment flows leave replaced reservation rows in {@code CANCELLED} status; request-level cancellation
     * must only consider and update still-active lines.
     */
    private static List<Reservation> activeReservationsOnly(List<Reservation> reservations) {
        return reservations.stream()
                .filter(r -> r.getStatus() == null || !"CANCELLED".equalsIgnoreCase(r.getStatus().trim()))
                .toList();
    }

    private void markRequestCancelled(ReservationRequest request, List<Reservation> reservations) {
        request.setStatus(ReservationRequest.Status.CANCELLED);
        request.setExpiresAt(null);
        requestRepo.save(request);

        for (Reservation reservation : reservations) {
            reservation.setStatus("CANCELLED");
            reservation.setExpiresAt(null);
        }
        reservationRepo.saveAll(reservations);

        List<Long> reservationIds = reservations.stream().map(Reservation::getId).toList();
        if (!reservationIds.isEmpty()) {
            List<Allocation> allocations = allocationRepo.findByReservationIdIn(reservationIds);
            for (Allocation allocation : allocations) {
                allocation.setStatus("CANCELLED");
                allocation.setExpiresAt(null);
            }
            allocationRepo.saveAll(allocations);
        }
    }

    private CancellationRequestDto toDto(CancellationRequest request) {
        return CancellationRequestDto.builder()
                .id(request.getId())
                .tenantId(request.getTenantId())
                .reservationRequestId(request.getReservationRequestId())
                .reservationRequestUrl(request.getReservationRequestUrl())
                .status(request.getStatus())
                .settlementMode(request.getSettlementMode())
                .currency(request.getCurrency())
                .cancelledAmount(request.getCancelledAmount())
                .releasedAmount(request.getReleasedAmount())
                .refundAmount(request.getRefundAmount())
                .penaltyAmount(request.getPenaltyAmount())
                .creditAmount(request.getCreditAmount())
                .sourceInvoiceId(request.getSourceInvoiceId())
                .stornoInvoiceId(request.getStornoInvoiceId())
                .creditNoteInvoiceId(request.getCreditNoteInvoiceId())
                .penaltyInvoiceId(request.getPenaltyInvoiceId())
                .finalInvoiceId(request.getFinalInvoiceId())
                .sourcePaymentTransactionId(request.getSourcePaymentTransactionId())
                .refundPaymentTransactionId(request.getRefundPaymentTransactionId())
                .note(request.getNote())
                .failureReason(request.getFailureReason())
                .createdAt(request.getCreatedAt())
                .completedAt(request.getCompletedAt())
                .build();
    }

    private PublicCancellationPreviewDto unavailablePreview(ReservationRequest reservationRequest, String message) {
        return PublicCancellationPreviewDto.builder()
                .canCancel(false)
                .status("UNAVAILABLE")
                .settlementMode(null)
                .currency(null)
                .cancelledAmount(amountZero())
                .releasedAmount(amountZero())
                .refundAmount(amountZero())
                .penaltyAmount(amountZero())
                .creditAmount(amountZero())
                .policyText(reservationRequest != null ? reservationRequest.getCancellationPolicyText() : null)
                .message(message)
                .build();
    }

    private String buildPublicPreviewMessage(String settlementMode,
                                             BigDecimal refundAmount,
                                             BigDecimal creditAmount,
                                             BigDecimal penaltyAmount,
                                             String currency) {
        if (MODE_CASH_REFUND.equals(settlementMode)) {
            if (penaltyAmount.compareTo(BigDecimal.ZERO) > 0) {
                return "If you cancel now, " + formatMoney(refundAmount, currency)
                        + " will be refunded and " + formatMoney(penaltyAmount, currency)
                        + " will be retained as a cancellation penalty.";
            }
            return "If you cancel now, " + formatMoney(refundAmount, currency) + " will be refunded.";
        }
        if (MODE_CUSTOMER_CREDIT.equals(settlementMode)) {
            if (penaltyAmount.compareTo(BigDecimal.ZERO) > 0) {
                return "If you cancel now, " + formatMoney(creditAmount, currency)
                        + " will remain as reusable booking credit and "
                        + formatMoney(penaltyAmount, currency)
                        + " will be retained as a cancellation penalty.";
            }
            return "If you cancel now, " + formatMoney(creditAmount, currency)
                    + " will remain as reusable booking credit.";
        }
        if (penaltyAmount.compareTo(BigDecimal.ZERO) > 0) {
            return "If you cancel now, " + formatMoney(penaltyAmount, currency)
                    + " will be retained as a cancellation penalty.";
        }
        return "If you cancel now, this reservation will be cancelled.";
    }

    private String formatMoney(BigDecimal amount, String currency) {
        return money(amount).toPlainString() + " " + firstNonBlank(currency, "EUR");
    }

    private String resolveReservationRequestUrl(Long reservationRequestId) {
        ReservationRequestAccessToken accessToken = accessTokenService.findActiveByReservationRequestId(reservationRequestId).orElse(null);
        if (accessToken != null) {
            return accessTokenService.buildPublicAccessUrl(accessToken.getTenantId(), accessToken.getToken());
        }
        return "/api/reservation-requests/" + reservationRequestId;
    }

    private void publishAutoFiscalizationIfRequired(Invoice invoice) {
        if (invoice != null && invoice.getId() != null && invoice.getFiscalizationStatus() != null
                && invoice.getFiscalizationStatus().name().equals("REQUIRED")) {
            eventPublisher.publishEvent(new InvoiceAutoFiscalizationRequestedEvent(invoice.getId()));
        }
    }

    private String normalizeSettlementMode(String settlementMode) {
        if (!StringUtils.hasText(settlementMode)) {
            return null;
        }
        String normalized = settlementMode.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case MODE_CASH_REFUND, MODE_CUSTOMER_CREDIT, MODE_NONE -> normalized;
            default -> throw new IllegalArgumentException("settlementMode must be CASH_REFUND, CUSTOMER_CREDIT, or NONE");
        };
    }

    private String normalizeNullable(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private String firstNonBlank(String primary, String fallback) {
        return StringUtils.hasText(primary) ? primary : fallback;
    }

    private BigDecimal zeroSafe(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private BigDecimal amountZero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal money(BigDecimal value) {
        return zeroSafe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal abs(BigDecimal value) {
        return zeroSafe(value).abs();
    }

    private BigDecimal min(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) <= 0 ? left : right;
    }
}
