package com.payflow.apigateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Validates X-API-Key at the edge before a request reaches any backend
 * route. Each backend service keeps its own identical check too (there's
 * no network isolation between the gateway and the backend ports locally,
 * so removing it there would be a real regression) -- this is a first
 * checkpoint, not a replacement.
 */
@Component
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    public static final String HEADER_NAME = "X-API-Key";
    private static final List<String> EXEMPT_PREFIXES = List.of("/actuator");

    private final String expectedApiKey;

    public AuthGlobalFilter(@Value("${payflow.security.api-key}") String expectedApiKey) {
        this.expectedApiKey = expectedApiKey;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getPath().value();
        if (EXEMPT_PREFIXES.stream().anyMatch(path::startsWith)
                || expectedApiKey.equals(request.getHeaders().getFirst(HEADER_NAME))) {
            return chain.filter(exchange);
        }

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        DataBuffer buffer = response.bufferFactory()
                .wrap(("{\"error\":\"missing or invalid " + HEADER_NAME + " header\"}")
                        .getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Must run before any route's RequestRateLimiter filter (which gets
     * order 0 as the sole filter on its route) so a bad-key request is
     * rejected before it can consume a rate-limit token.
     */
    @Override
    public int getOrder() {
        return -100;
    }
}
