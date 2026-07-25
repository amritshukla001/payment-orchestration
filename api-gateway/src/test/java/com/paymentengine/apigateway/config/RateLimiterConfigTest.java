package com.payflow.apigateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import reactor.test.StepVerifier;

class RateLimiterConfigTest {

    private final KeyResolver resolver = new RateLimiterConfig().apiKeyResolver();

    @Test
    void resolvesTheKeyFromTheApiKeyHeaderWhenPresent() {
        ServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/payments").header(AuthGlobalFilter.HEADER_NAME, "client-a-key").build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("client-a-key")
                .verifyComplete();
    }

    @Test
    void fallsBackToAnonymousWhenTheHeaderIsMissing() {
        ServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/payments").build());

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext("anonymous")
                .verifyComplete();
    }
}
