package com.payflow.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class AuthGlobalFilterTest {

    private final AuthGlobalFilter filter = new AuthGlobalFilter("expected-key");

    @Test
    void rejectsARequestWithNoApiKeyHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/sagas").build());
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, markingChain(chainInvoked))).verifyComplete();

        assertThat(chainInvoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void rejectsARequestWithTheWrongApiKeyHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sagas").header(AuthGlobalFilter.HEADER_NAME, "wrong-key").build());
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, markingChain(chainInvoked))).verifyComplete();

        assertThat(chainInvoked).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void passesThroughARequestWithTheCorrectApiKeyHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/sagas").header(AuthGlobalFilter.HEADER_NAME, "expected-key").build());
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, markingChain(chainInvoked))).verifyComplete();

        assertThat(chainInvoked).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    void exemptsActuatorEndpointsWithNoApiKeyHeader() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/actuator/health").build());
        AtomicBoolean chainInvoked = new AtomicBoolean(false);

        StepVerifier.create(filter.filter(exchange, markingChain(chainInvoked))).verifyComplete();

        assertThat(chainInvoked).isTrue();
    }

    private static GatewayFilterChain markingChain(AtomicBoolean invoked) {
        return exchange -> {
            invoked.set(true);
            return Mono.empty();
        };
    }
}
