package com.stackwizard.booking_api.service.opera;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DefaultOperaPostingClientTokenTest {
    private static final String BASE = "https://opera.test";
    private static final OperaTenantConfigResolver.OperaResolvedConfig CONFIG = new OperaTenantConfigResolver.OperaResolvedConfig(
            BASE, "/oauth/v1/tokens", "app", "client", "secret", "ENT", null);

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-14T10:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };

    private static String token(String value) {
        return "{\"access_token\":\"" + value + "\",\"expires_in\":3600}";
    }

    @Test
    void reusesTokenUntilShortlyBeforeExpiry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DefaultOperaPostingClient client = new DefaultOperaPostingClient(builder, clock);

        server.expect(once(), requestTo(BASE + "/oauth/v1/tokens")).andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(token("t1"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/a")).andExpect(header("Authorization", "Bearer t1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/b")).andExpect(header("Authorization", "Bearer t1")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));
        server.expect(once(), requestTo(BASE + "/oauth/v1/tokens"))
                .andRespond(withSuccess(token("t2"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/c")).andExpect(header("Authorization", "Bearer t2")).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get(CONFIG, "CH", "DH", "/a", Map.of());
        client.get(CONFIG, "CH", "DH", "/b", Map.of());
        now.set(now.get().plus(Duration.ofMinutes(59)));
        client.get(CONFIG, "CH", "DH", "/c", Map.of());

        server.verify();
    }

    @Test
    void refusedTokenIsRenewedAndTheGetRetriedOnce() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        DefaultOperaPostingClient client = new DefaultOperaPostingClient(builder, clock);

        server.expect(requestTo(BASE + "/oauth/v1/tokens")).andRespond(withSuccess(token("old"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/a")).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        server.expect(requestTo(BASE + "/oauth/v1/tokens")).andRespond(withSuccess(token("new"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BASE + "/a")).andExpect(header("Authorization", "Bearer new"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        client.get(CONFIG, "CH", "DH", "/a", Map.of());

        server.verify();
    }
}
