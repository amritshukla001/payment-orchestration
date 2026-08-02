package com.payflow.paymentapi.stripe;

import com.payflow.paymentapi.service.InvalidCardTokenException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

/**
 * Confirms a CARD payment's Stripe PaymentMethod ID (tokenized client-side
 * by Stripe Elements, so raw card data never reaches this service) is real
 * before the payment is allowed to enter the saga. Modeled directly on
 * payment-engine's GeminiSummaryClient: a plain RestClient call (no Stripe
 * SDK needed for one GET request), guarded by a circuit breaker, degrading
 * to a no-op when no API key is configured -- the normal local-dev/CI
 * state, not an error.
 *
 * Unlike GeminiSummaryClient, a circuit-open/network failure here fails
 * *open* (lets the payment proceed unverified) rather than closed. Real
 * payment gateways usually do the opposite for fraud reasons; this is a
 * demo project, and a third-party outage blocking the whole checkout flow
 * would be a worse trade than an occasional unverified card in a system
 * that doesn't move real money regardless.
 */
@Component
public class StripeCardTokenVerifier {

    private static final Logger log = LoggerFactory.getLogger(StripeCardTokenVerifier.class);
    private static final String PAYMENT_METHODS_URL = "https://api.stripe.com/v1/payment_methods/";

    private final RestClient restClient;
    private final String apiKey;

    public StripeCardTokenVerifier(@Value("${payflow.stripe.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.restClient = RestClient.create();
    }

    @CircuitBreaker(name = "stripe-verifier", fallbackMethod = "verifyFallback")
    public void verify(String cardToken) {
        if (apiKey == null || apiKey.isBlank()) {
            log.debug("No Stripe API key configured -- skipping card token verification");
            return;
        }
        if (cardToken == null || cardToken.isBlank()) {
            throw new InvalidCardTokenException(
                    "A card token is required for CARD payments when Stripe verification is enabled");
        }

        try {
            restClient.get()
                    .uri(PAYMENT_METHODS_URL + cardToken)
                    .header("Authorization", "Bearer " + apiKey)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpClientErrorException e) {
            throw new InvalidCardTokenException("Stripe rejected this card token: " + e.getStatusCode());
        }
    }

    // resilience4j-spring routes a thrown exception to the most specific
    // matching fallback overload. Without this one, the Throwable
    // catch-all below would swallow a legitimate "this card is invalid"
    // rejection into a silent pass-through -- ignore-exceptions in
    // application.yml only keeps it off the circuit breaker's failure-rate
    // metric, it does NOT stop the fallback from being invoked.
    private void verifyFallback(String cardToken, InvalidCardTokenException e) {
        throw e;
    }

    // Only reached for genuine external failures (network errors, 5xx,
    // circuit open).
    private void verifyFallback(String cardToken, Throwable t) {
        log.warn("Stripe API unavailable -- letting the CARD payment proceed unverified "
                + "(demo project, not a production payment gateway)", t);
    }
}
