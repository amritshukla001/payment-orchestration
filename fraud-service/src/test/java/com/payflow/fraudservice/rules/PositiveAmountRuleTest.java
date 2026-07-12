package com.payflow.fraudservice.rules;

import com.payflow.common.commands.CheckFraudCommand;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PositiveAmountRuleTest {

    private final PositiveAmountRule rule = new PositiveAmountRule();

    @Test
    void allowsAPositiveAmount() {
        assertThat(rule.checkForRejection(commandFor(500L))).isEmpty();
    }

    @Test
    void rejectsZero() {
        assertThat(rule.checkForRejection(commandFor(0L))).isPresent();
    }

    @Test
    void rejectsNegativeAmounts() {
        Optional<String> result = rule.checkForRejection(commandFor(-100L));

        assertThat(result).isPresent();
        assertThat(result.get()).contains("Non-positive");
    }

    private CheckFraudCommand commandFor(long amountCents) {
        return new CheckFraudCommand(UUID.randomUUID(), UUID.randomUUID(), amountCents, "USD", Instant.now());
    }
}
