package com.stackwizard.booking_api.service;

import com.stackwizard.booking_api.model.Product;
import com.stackwizard.booking_api.model.Reservation;
import com.stackwizard.booking_api.model.ReservationRequest;
import com.stackwizard.booking_api.repository.ProductRepository;
import com.stackwizard.booking_api.repository.ReservationRepository;
import com.stackwizard.booking_api.repository.ReservationRequestRepository;
import com.stackwizard.booking_api.dto.PublicCancellationPreviewDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ReservationConfirmationEmailService {
    private static final Logger log = LoggerFactory.getLogger(ReservationConfirmationEmailService.class);

    private final ReservationRequestRepository reservationRequestRepository;
    private final ReservationRepository reservationRepository;
    private final ProductRepository productRepository;
    private final PaymentService paymentService;
    private final ReservationConfirmationEmailRenderer renderer;
    private final TenantEmailConfigResolver tenantEmailConfigResolver;
    private final CancellationService cancellationService;
    private final ReservationRequestAccessTokenService accessTokenService;

    public ReservationConfirmationEmailService(ReservationRequestRepository reservationRequestRepository,
                                               ReservationRepository reservationRepository,
                                               ProductRepository productRepository,
                                               PaymentService paymentService,
                                               ReservationConfirmationEmailRenderer renderer,
                                               TenantEmailConfigResolver tenantEmailConfigResolver,
                                               CancellationService cancellationService,
                                               ReservationRequestAccessTokenService accessTokenService) {
        this.reservationRequestRepository = reservationRequestRepository;
        this.reservationRepository = reservationRepository;
        this.productRepository = productRepository;
        this.paymentService = paymentService;
        this.renderer = renderer;
        this.tenantEmailConfigResolver = tenantEmailConfigResolver;
        this.cancellationService = cancellationService;
        this.accessTokenService = accessTokenService;
    }

    @Transactional
    public boolean sendIfEligible(Long reservationRequestId) {
        return sendInternal(reservationRequestId, false).status() == DispatchStatus.SENT;
    }

    @Transactional
    public DispatchResult sendForDebug(Long reservationRequestId, boolean force) {
        try {
            return sendInternal(reservationRequestId, force);
        } catch (Exception ex) {
            log.error("Debug send of reservation confirmation email failed for request {}", reservationRequestId, ex);
            return DispatchResult.failed(
                    reservationRequestId,
                    "Failed to send reservation confirmation email: " + rootCauseMessage(ex)
            );
        }
    }

    private DispatchResult sendInternal(Long reservationRequestId, boolean force) {
        ReservationRequest request = reservationRequestRepository.findById(reservationRequestId).orElse(null);
        if (request == null) {
            return DispatchResult.failed(reservationRequestId, "Reservation request not found");
        }
        if (request.getStatus() != ReservationRequest.Status.FINALIZED) {
            return DispatchResult.skipped(reservationRequestId, "Reservation request is not FINALIZED");
        }
        if (request.getConfirmationEmailSentAt() != null && !force) {
            return DispatchResult.skipped(
                    reservationRequestId,
                    "Confirmation email already sent at " + request.getConfirmationEmailSentAt(),
                    request.getCustomerEmail(),
                    request.getConfirmationEmailSentAt(),
                    false
            );
        }
        if (!StringUtils.hasText(request.getCustomerEmail())) {
            return DispatchResult.skipped(reservationRequestId, "Customer email is missing");
        }

        TenantEmailConfigResolver.EmailResolvedConfig emailConfig = tenantEmailConfigResolver
                .findActive(request.getTenantId())
                .orElse(null);
        if (emailConfig == null) {
            return DispatchResult.skipped(reservationRequestId, "Tenant email config is missing or inactive");
        }

        List<Reservation> reservations = reservationRepository.findByRequestId(reservationRequestId);
        if (reservations.isEmpty()) {
            log.warn("Skipping reservation confirmation email for request {} because it has no reservations", reservationRequestId);
            return DispatchResult.skipped(reservationRequestId, "Reservation request has no reservations");
        }

        Map<Long, Product> productsById = productRepository.findAllById(
                        reservations.stream()
                                .map(Reservation::getProductId)
                                .filter(java.util.Objects::nonNull)
                                .distinct()
                                .toList())
                .stream()
                .collect(Collectors.toMap(Product::getId, product -> product));

        PaymentService.RequestPaymentSummary paymentSummary =
                paymentService.summarizeReservationRequest(request.getId(), request.getTenantId(), reservations);
        String publicAccessUrl = accessTokenService.findActiveByReservationRequestId(request.getId())
                .map(token -> accessTokenService.buildPublicAccessUrl(token.getTenantId(), token.getToken()))
                .orElse(null);
        PublicCancellationPreviewDto publicCancellation =
                publicAccessUrl != null ? cancellationService.previewPublic(request.getId()) : null;
        ReservationConfirmationEmailRenderer.RenderedEmail rendered =
                renderer.render(request, reservations, productsById, paymentSummary, emailConfig, publicCancellation, publicAccessUrl);

        try {
            String customerEmail = request.getCustomerEmail().trim();
            ReservationEmailDispatchHelper.sendMime(
                    emailConfig,
                    customerEmail,
                    rendered.subject(),
                    rendered.plainText(),
                    rendered.html()
            );
            OffsetDateTime sentAt = OffsetDateTime.now();
            request.setConfirmationEmailSentAt(sentAt);
            reservationRequestRepository.save(request);
            log.info("Sent reservation confirmation email for request {} to {}", request.getId(), request.getCustomerEmail());
            return DispatchResult.sent(request.getId(), request.getCustomerEmail(), sentAt, force);
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Failed to send reservation confirmation email for request " + reservationRequestId
                            + ": " + rootCauseMessage(ex),
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

    public enum DispatchStatus {
        SENT,
        SKIPPED,
        FAILED
    }

    public record DispatchResult(
            Long reservationRequestId,
            DispatchStatus status,
            String message,
            String recipient,
            OffsetDateTime sentAt,
            boolean forced
    ) {
        public static DispatchResult sent(Long reservationRequestId,
                                          String recipient,
                                          OffsetDateTime sentAt,
                                          boolean forced) {
            return new DispatchResult(
                    reservationRequestId,
                    DispatchStatus.SENT,
                    "Confirmation email sent",
                    recipient,
                    sentAt,
                    forced
            );
        }

        public static DispatchResult skipped(Long reservationRequestId, String message) {
            return new DispatchResult(reservationRequestId, DispatchStatus.SKIPPED, message, null, null, false);
        }

        public static DispatchResult skipped(Long reservationRequestId,
                                             String message,
                                             String recipient,
                                             OffsetDateTime sentAt,
                                             boolean forced) {
            return new DispatchResult(reservationRequestId, DispatchStatus.SKIPPED, message, recipient, sentAt, forced);
        }

        public static DispatchResult failed(Long reservationRequestId, String message) {
            return new DispatchResult(reservationRequestId, DispatchStatus.FAILED, message, null, null, false);
        }
    }
}
