package com.stackwizard.booking_api.service.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stackwizard.booking_api.model.EventDelivery;
import com.stackwizard.booking_api.model.EventSubscription;
import com.stackwizard.booking_api.model.IntegrationEvent;
import com.stackwizard.booking_api.repository.EventDeliveryRepository;
import com.stackwizard.booking_api.repository.EventSubscriptionRepository;
import com.stackwizard.booking_api.repository.IntegrationEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;

/**
 * Delivers outbox events to webhook subscribers, at least once. Any instance may run it: deliveries are claimed
 * with {@code for update skip locked} and a lease. Consumers dedupe on {@code X-Event-Id}.
 */
@Service
public class IntegrationEventDispatcher {
    private static final Logger log = LoggerFactory.getLogger(IntegrationEventDispatcher.class);
    /** Backoff after attempt n (1-based); after the last one the delivery is DEAD (replayable from admin). */
    static final List<Duration> BACKOFF = List.of(Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(12));

    private final EventDeliveryRepository deliveryRepo;
    private final EventSubscriptionRepository subscriptionRepo;
    private final IntegrationEventRepository eventRepo;
    private final TransactionTemplate tx;
    private final HttpClient http;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final boolean enabled;

    public IntegrationEventDispatcher(EventDeliveryRepository deliveryRepo,
                                      EventSubscriptionRepository subscriptionRepo,
                                      IntegrationEventRepository eventRepo,
                                      TransactionTemplate tx,
                                      @Value("${integration.events.dispatch-enabled:true}") boolean enabled) {
        this.deliveryRepo = deliveryRepo;
        this.subscriptionRepo = subscriptionRepo;
        this.eventRepo = eventRepo;
        this.tx = tx;
        this.enabled = enabled;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Scheduled(fixedDelayString = "${integration.events.dispatch-ms:5000}", initialDelayString = "${integration.events.dispatch-initial-ms:30000}")
    public void dispatchDue() {
        if (!enabled) {
            return;
        }
        List<Long> ids = tx.execute(status -> deliveryRepo.claimDue(50));
        if (ids == null) {
            return;
        }
        for (Long id : ids) {
            try {
                deliver(id);
            } catch (Exception ex) {
                log.warn("Event delivery {} failed unexpectedly", id, ex);
            }
        }
    }

    void deliver(Long deliveryId) {
        EventDelivery delivery = deliveryRepo.findById(deliveryId).orElse(null);
        if (delivery == null || delivery.getStatus() != EventDelivery.Status.PENDING) {
            return;
        }
        EventSubscription sub = subscriptionRepo.findById(delivery.getSubscriptionId()).orElse(null);
        IntegrationEvent event = eventRepo.findById(delivery.getEventId()).orElse(null);
        if (sub == null || event == null || !Boolean.TRUE.equals(sub.getActive())) {
            delivery.setStatus(EventDelivery.Status.DEAD);
            delivery.setLastError("subscription inactive or missing");
            delivery.setLeaseUntil(null);
            deliveryRepo.save(delivery);
            return;
        }
        Integer statusCode = null;
        String error = null;
        try {
            String body = objectMapper.writeValueAsString(event.getPayload());
            HttpRequest request = HttpRequest.newBuilder(URI.create(sub.getEndpointUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Event-Id", event.getEventUuid().toString())
                    .header("X-Event-Type", event.getType())
                    .header("X-Signature", "sha256=" + sign(sub.getSecret(), body))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            statusCode = response.statusCode();
            if (statusCode < 200 || statusCode >= 300) {
                error = truncate(response.body());
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error = "interrupted";
        } catch (Exception ex) {
            error = truncate(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        applyOutcome(delivery, statusCode, error, OffsetDateTime.now());
        deliveryRepo.save(delivery);
    }

    static void applyOutcome(EventDelivery delivery, Integer statusCode, String error, OffsetDateTime now) {
        int attempts = delivery.getAttempts() + 1;
        delivery.setAttempts(attempts);
        delivery.setLastStatus(statusCode);
        delivery.setLeaseUntil(null);
        if (error == null) {
            delivery.setStatus(EventDelivery.Status.SENT);
            delivery.setSentAt(now);
            delivery.setLastError(null);
            return;
        }
        delivery.setLastError(error);
        if (attempts > BACKOFF.size()) {
            delivery.setStatus(EventDelivery.Status.DEAD);
        } else {
            delivery.setNextAttemptAt(now.plus(BACKOFF.get(attempts - 1)));
        }
    }

    public static String sign(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() > 1000 ? s.substring(0, 1000) : s;
    }
}
