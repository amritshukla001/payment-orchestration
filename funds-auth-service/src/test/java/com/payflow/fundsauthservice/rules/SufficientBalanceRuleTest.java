package com.payflow.fundsauthservice.rules;

import com.payflow.common.enums.PaymentMethod;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SufficientBalanceRuleTest {

    private final SufficientBalanceRule rule = new SufficientBalanceRule();

    @Test
    void rejectsWhenBalanceIsBelowTheRequestedAmount() {
        FundsAuthorizationContext context = new FundsAuthorizationContext(
                UUID.randomUUID(), 5_000L, 100L, PaymentMethod.CARD, "HDFC");

        Optional<String> result = rule.checkForRejection(context);

        assertThat(result).contains("Insufficient funds: balance 100 < requested 5000");
    }

    @Test
    void approvesWhenBalanceCoversTheRequestedAmount() {
        FundsAuthorizationContext context = new FundsAuthorizationContext(
                UUID.randomUUID(), 5_000L, 100_000L, PaymentMethod.CARD, "HDFC");

        Optional<String> result = rule.checkForRejection(context);

        assertThat(result).isEmpty();
    }
}
