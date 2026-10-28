package com.stackwizard.booking_api.service.integration;

import com.stackwizard.booking_api.model.EventDelivery;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationEventDispatcherTest {
    private static final String EXPECTED_HMAC = "aa9e2e3575f5d7098b6caccd790888c36d5fdb63342a73bada2d6a51747a8494";
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-28T10:00:00+01:00");

    @Test
    void successMarksSent() {
        EventDelivery d = delivery(0);
        IntegrationEventDispatcher.applyOutcome(d, 200, null, NOW);
        assertThat(d.getStatus()).isEqualTo(EventDelivery.Status.SENT);
        assertThat(d.getSentAt()).isEqualTo(NOW);
        assertThat(d.getAttempts()).isEqualTo(1);
    }

    @Test
    void failureBacksOffThenDies() {
        EventDelivery d = delivery(0);
        IntegrationEventDispatcher.applyOutcome(d, 500, "boom", NOW);
        assertThat(d.getStatus()).isEqualTo(EventDelivery.Status.PENDING);
        assertThat(d.getNextAttemptAt()).isEqualTo(NOW.plusMinutes(1));
        EventDelivery last = delivery(IntegrationEventDispatcher.BACKOFF.size());
        IntegrationEventDispatcher.applyOutcome(last, null, "timeout", NOW);
        assertThat(last.getStatus()).isEqualTo(EventDelivery.Status.DEAD);
    }

    @Test
    void hmacIsStableHexSha256() {
        // echo -n '{"a":1}' | openssl dgst -sha256 -hmac secret
        assertThat(IntegrationEventDispatcher.sign("secret", "{\"a\":1}")).isEqualTo(EXPECTED_HMAC);
    }

    @Test
    void typeMatching() {
        assertThat(EventTypeMatcher.matches(new String[]{"*"}, "loyalty.tier.upgraded")).isTrue();
        assertThat(EventTypeMatcher.matches(new String[]{"loyalty.*"}, "loyalty.tier.upgraded")).isTrue();
        assertThat(EventTypeMatcher.matches(new String[]{"loyalty.tier.*"}, "loyalty.stay.posted")).isFalse();
        assertThat(EventTypeMatcher.matches(new String[]{"reservation.confirmed"}, "reservation.confirmed")).isTrue();
        assertThat(EventTypeMatcher.matches(new String[]{"reservation.confirmed"}, "reservation.cancelled")).isFalse();
    }

    private static EventDelivery delivery(int attempts) {
        return EventDelivery.builder().status(EventDelivery.Status.PENDING).attempts(attempts).nextAttemptAt(NOW).build();
    }
}
