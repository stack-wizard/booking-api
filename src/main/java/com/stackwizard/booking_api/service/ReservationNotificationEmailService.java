package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.CancellationRequest;
import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.CancellationRequestRepository;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Sends transactional emails for cancellation and reservation amendments (SMTP config from tenant integration).
 */
public class ReservationNotificationEmailService {

    private static final Logger log = LoggerFactory.getLogger(ReservationNotificationEmailService.class);

    private final ReservationRequestRepository reservationRequestRepository;
    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final PaymentService paymentService;
    private final ReservationConfirmationEmailRenderer renderer;
    private final TenantEmailConfigResolver tenantEmailConfigResolver;
    private final CancellationRequestRepository cancellationRequestRepository;

    public ReservationNotificationEmailService(ReservationRequestRepository reservationRequestRepository,
                                               ReservationRepository reservationRepository,
                                               ProductRepository productRepository,
                                               PaymentService paymentService,
                                               ReservationConfirmationEmailRenderer renderer,
                                               TenantEmailConfigResolver tenantEmailConfigResolver,
                                               CancellationRequestRepository cancellationRequestRepository) {
        this.reservationRequestRepository = reservationRequestRepository;
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.paymentService = paymentService;
        this.renderer = renderer;
        this.tenantEmailConfigResolver = tenantEmailConfigResolver;
        this.cancellationRequestRepository = cancellationRequestRepository;
    }

    @Transactional(readOnly = true)
    public void sendCancellationEmail(Long reservationRequestId, Long cancellationRequestId) {
        ReservationRequest request = reservationRequestRepository.findById(reservationRequestId).orElse(null);
        if (request == null) {
            log.warn("Skipping cancellation email: reservation request {} not found", reservationRequestId);
            return;
        }
        if (!StringUtils.hasText(request.getCustomerEmail())) {
            log.info("Skipping cancellation email for request {}: customer email missing", reservationRequestId);
            return;
        }
        CancellationRequest cancellation = cancellationRequestRepository.findById(cancellationRequestId).orElse(null);
        if (cancellation == null || !Objects.equals(cancellation.getReservationRequestId(), reservationRequestId)) {
            log.warn("Skipping cancellation email: cancellation {} invalid for request {}", cancellationRequestId, reservationRequestId);
            return;
        }

        TenantEmailConfigResolver.EmailResolvedConfig emailConfig = tenantEmailConfigResolver
                .findActive(request.getTenantId())
                .orElse(null);
        if (emailConfig == null) {
            log.info("Skipping cancellation email for request {}: tenant email inactive or missing", reservationRequestId);
            return;
        }

        List<Reservation> reservations = reservationRepository.findByRequestId(reservationRequestId).stream()
                .sorted(Comparator
                        .comparing(Reservation::getStartsAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(Reservation::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (reservations.isEmpty()) {
            log.warn("Skipping cancellation email for request {}: no reservation lines", reservationRequestId);
            return;
        }

        Map<Long, Product> productsById = productRepository.findAllById(
                        reservations.stream()
                                .map(Reservation::getProductId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        ReservationConfirmationEmailRenderer.RenderedEmail rendered =
                renderer.renderCancellationEmail(request, reservations, productsById, cancellation, emailConfig);
        try {
            ReservationEmailDispatchHelper.sendMime(
                    emailConfig,
                    request.getCustomerEmail().trim(),
                    rendered.subject(),
                    rendered.plainText(),
                    rendered.html()
            );
            log.info("Sent cancellation email for request {} to {}", request.getId(), request.getCustomerEmail());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to send cancellation email for reservation request " + reservationRequestId + ": " + rootCauseMessage(ex),
                    ex
            );
        }
    }

    @Transactional(readOnly = true)
    public void sendAmendmentEmail(Long reservationRequestId) {
        ReservationRequest request = reservationRequestRepository.findById(reservationRequestId).orElse(null);
        if (request == null) {
            log.warn("Skipping amendment email: reservation request {} not found", reservationRequestId);
            return;
        }
        if (!StringUtils.hasText(request.getCustomerEmail())) {
            log.info("Skipping amendment email for request {}: customer email missing", reservationRequestId);
            return;
        }

        TenantEmailConfigResolver.EmailResolvedConfig emailConfig = tenantEmailConfigResolver
                .findActive(request.getTenantId())
                .orElse(null);
        if (emailConfig == null) {
            log.info("Skipping amendment email for request {}: tenant email inactive or missing", reservationRequestId);
            return;
        }

        List<Reservation> reservations = reservationRepository.findByRequestId(reservationRequestId).stream()
                .filter(r -> r.getStatus() == null || !"CANCELLED".equalsIgnoreCase(r.getStatus().trim()))
                .sorted(Comparator
                        .comparing(Reservation::getStartsAt, Comparator.nullsLast(LocalDateTime::compareTo))
                        .thenComparing(Reservation::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (reservations.isEmpty()) {
            log.warn("Skipping amendment email for request {}: no active reservation lines", reservationRequestId);
            return;
        }

        Map<Long, Product> productsById = productRepository.findAllById(
                        reservations.stream()
                                .map(Reservation::getProductId)
                                .filter(Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        PaymentService.RequestPaymentSummary paymentSummary =
                paymentService.summarizeReservationRequest(request.getId(), request.getTenantId(), reservations);

        ReservationConfirmationEmailRenderer.RenderedEmail rendered =
                renderer.renderAmendmentEmail(request, reservations, productsById, paymentSummary, emailConfig);
        try {
            ReservationEmailDispatchHelper.sendMime(
                    emailConfig,
                    request.getCustomerEmail().trim(),
                    rendered.subject(),
                    rendered.plainText(),
                    rendered.html()
            );
            log.info("Sent amendment email for request {} to {}", request.getId(), request.getCustomerEmail());
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to send amendment email for reservation request " + reservationRequestId + ": " + rootCauseMessage(ex),
                    ex
            );
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        String message = throwable.getMessage();
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
            if (StringUtils.hasText(current.getMessage())) {
                message = current.getMessage();
            }
        }
        if (!StringUtils.hasText(message)) {
            message = current.getClass().getSimpleName();
        }
        return message;
    }
}
