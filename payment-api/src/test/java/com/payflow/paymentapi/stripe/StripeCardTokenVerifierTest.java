package com.payflow.paymentapi.stripe;

import com.payflow.paymentapi.service.InvalidCardTokenException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers the two branches that don't require mocking an HTTP call --
 * mirrors GeminiSummaryClient's own precedent of not testing its live
 * external call either, since no HTTP-mocking library is set up in this
 * codebase.
 */
class StripeCardTokenVerifierTest {

    @Test
    void skipsVerificationWhenNoApiKeyIsConfigured() {
        StripeCardTokenVerifier verifier = new StripeCardTokenVerifier("");

        assertThatCode(() -> verifier.verify(null)).doesNotThrowAnyException();
        assertThatCode(() -> verifier.verify("pm_something")).doesNotThrowAnyException();
    }

    @Test
    void rejectsABlankCardTokenWhenAnApiKeyIsConfigured() {
        StripeCardTokenVerifier verifier = new StripeCardTokenVerifier("sk_test_fake");

        assertThatThrownBy(() -> verifier.verify(null))
                .isInstanceOf(InvalidCardTokenException.class);
        assertThatThrownBy(() -> verifier.verify(" "))
                .isInstanceOf(InvalidCardTokenException.class);
    }
}
